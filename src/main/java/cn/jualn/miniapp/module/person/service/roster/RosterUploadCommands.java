package cn.jualn.miniapp.module.person.service.roster;

import cn.jualn.miniapp.module.person.service.facts.PersonFactReaders;
import cn.jualn.miniapp.module.person.service.support.PersonnelJournal;
import cn.jualn.miniapp.module.person.service.support.PersonnelValues;
import cn.jualn.miniapp.module.person.service.support.PersonPrivateData;

import org.springframework.transaction.annotation.Isolation;
import cn.jualn.miniapp.module.person.bo.QualificationBO.Column;
import cn.jualn.miniapp.module.person.bo.QualificationBO.Import;
import cn.jualn.miniapp.module.person.bo.QualificationBO.Resource;
import cn.jualn.miniapp.module.person.bo.QualificationBO.UploadFingerprint;
import cn.jualn.miniapp.module.person.bo.QualificationBO.UploadReservation;

import cn.jualn.miniapp.module.person.entity.PersonRows;
import cn.jualn.miniapp.module.person.entity.QualificationRows;

import cn.jualn.miniapp.module.person.mapper.PersonMapper;
import cn.jualn.miniapp.module.person.mapper.QualificationMapper;

import java.util.Base64;
import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static cn.jualn.miniapp.module.person.service.support.PersonnelValues.encode;
import static cn.jualn.miniapp.module.person.service.support.PersonnelValues.id;
import static cn.jualn.miniapp.module.person.service.support.PersonnelValues.key;
import static cn.jualn.miniapp.module.person.service.support.PersonnelValues.one;
import static cn.jualn.miniapp.module.person.service.support.PersonnelValues.problem;
import static cn.jualn.miniapp.module.person.service.support.PersonnelValues.text;
import static cn.jualn.miniapp.module.person.service.support.PersonnelValues.unavailable;

/** Short durable registration and finalization transactions; XLSX parsing occurs between them. */
@Service @RequiredArgsConstructor @Transactional(isolation=Isolation.REPEATABLE_READ)
public class RosterUploadCommands {
    private final PersonFactReaders readers;
    private final PersonMapper facts;
    private final QualificationMapper mapper;
    private final RosterAccess access;
    private final PersonPrivateData secrets;
    private final PersonnelJournal journal;
    private final cn.jualn.miniapp.module.person.service.profile.PersonIdentifierNamespaces namespaces;
    public UploadReservation reserve(long actor,String organization,String source,byte[] bytes,String key) {
        var c=readers.open(cn.jualn.miniapp.module.person.service.access.PersonAuthorityGate.Access.EXCLUSIVE,actor);access.requireOrganization(actor,organization,true,c.evaluatedAt());access.active(organization);key(key);
        var command=new UploadFingerprint(organization,text(source,256,"sourceReference"),secrets.digest("workbook",Base64.getEncoder().encodeToString(bytes)));
        var prior=facts.receipt(actor,"createPartyRosterImport",organization,key);
        if(prior!=null && !journal.fingerprint(command).equals(prior.getFingerprint())) throw problem(org.springframework.http.HttpStatus.CONFLICT,"people-idempotency-conflict");
        if(prior!=null && prior.getStatus()!=202) return new UploadReservation(command,journal.replay(actor,"createPartyRosterImport",organization,key,command,Import.class));
        if(prior!=null) {
            if(prior.getCreatedAt().plusMinutes(2).isAfter(c.evaluatedAt())) throw problem(org.springframework.http.HttpStatus.CONFLICT,"people-operation-in-progress");
            one(mapper.restartUpload(prior.getId(),c.evaluatedAt()));
        }
        if(prior==null) {
            var receipt=new PersonRows.Receipt();receipt.setId(id());receipt.setActor(actor);receipt.setOperation("createPartyRosterImport");receipt.setTarget(organization);
            receipt.setCommandKey(key);receipt.setFingerprint(journal.fingerprint(command));receipt.setStatus(202);receipt.setResultJson("{}");receipt.setEtag("");receipt.setCreatedAt(c.evaluatedAt());one(facts.insertReceipt(receipt));
        }
        return new UploadReservation(command,null);
    }
    public Resource<Import> complete(long actor,UploadFingerprint command,byte[] bytes,List<Column> columns,String key) {
        var c=readers.open(cn.jualn.miniapp.module.person.service.access.PersonAuthorityGate.Access.EXCLUSIVE,actor);access.requireOrganization(actor,command.organizationId(),true,c.evaluatedAt());access.active(command.organizationId());
        var replay=journal.replay(actor,"createPartyRosterImport",command.organizationId(),key,command,Import.class);if(replay!=null) return replay;
        var pending=facts.receipt(actor,"createPartyRosterImport",command.organizationId(),key);
        if(pending==null || pending.getStatus()!=202 || !journal.fingerprint(command).equals(pending.getFingerprint())) throw unavailable();
        var row=new QualificationRows.Import();row.setId(id());row.setCreator(actor);row.setOrganizationId(command.organizationId());row.setSourceReference(command.sourceReference());
        row.setFileFingerprint(command.fileFingerprint());row.setWorkbook(secrets.encrypt("workbook",bytes));row.setColumnsJson(encode(columns));row.setState("UPLOADED");row.setRevision(1);
        row.setExpiresAt(c.evaluatedAt().plusHours(24));row.setFileRetainUntil(row.getExpiresAt().plusHours(24));
        row.setNamespace(namespaces.items().get(0).namespace());row.setLastEventId(id());
        var result=journal.append(actor,"createPartyRosterImport",row.getId(),key,command,row.getLastEventId(),"ROSTER_IMPORT",1,null,PartyRosterService.project(row,c.evaluatedAt()),
            "Upload roster source",command.sourceReference(),"ADMIN_VERIFIED_SOURCE",command.sourceReference(),1L,actor,c.evaluatedAt(),201,"/v1/admin/party-roster-imports/"+row.getId());
        one(mapper.insertImport(row));access.requireOrganization(actor,command.organizationId(),true,facts.now());access.active(command.organizationId());return result;
    }
    public void abandonKnownParseFailure(long actor,UploadFingerprint command,String key) {
        mapper.abandonUpload(actor,command.organizationId(),key,journal.fingerprint(command));
    }
}
