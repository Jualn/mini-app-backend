package cn.jualn.miniapp.module.person.service.support;


import org.springframework.transaction.annotation.Propagation;
import cn.jualn.miniapp.module.person.bo.QualificationBO.Resource;
import cn.jualn.miniapp.module.person.entity.PersonRows;
import cn.jualn.miniapp.module.person.mapper.PersonMapper;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static cn.jualn.miniapp.module.person.service.support.PersonnelValues.JSON;
import static cn.jualn.miniapp.module.person.service.support.PersonnelValues.decode;
import static cn.jualn.miniapp.module.person.service.support.PersonnelValues.encode;
import static cn.jualn.miniapp.module.person.service.support.PersonnelValues.etag;
import static cn.jualn.miniapp.module.person.service.support.PersonnelValues.id;
import static cn.jualn.miniapp.module.person.service.support.PersonnelValues.key;
import static cn.jualn.miniapp.module.person.service.support.PersonnelValues.one;
import static cn.jualn.miniapp.module.person.service.support.PersonnelValues.problem;
import static cn.jualn.miniapp.module.person.service.support.PersonnelValues.reason;
import static cn.jualn.miniapp.module.person.service.support.PersonnelValues.unavailable;

/** Existing permanent Person receipt/event protocol; caller owns gate and transaction. */
@Service @RequiredArgsConstructor
@Transactional(propagation=Propagation.MANDATORY)
public class PersonnelJournal {
    @org.springframework.beans.factory.annotation.Autowired
    private cn.jualn.miniapp.module.admin.operation.service.AdminAuditJournal managementAudit;

    private final PersonMapper mapper;
    private final PersonPrivateData secrets;
    private final cn.jualn.miniapp.module.person.mapper.QualificationMapper qualifications;
    public String fingerprint(Object command) {return secrets.digest("command",encode(command));}
    public <T> Resource<T> replay(long actor,String operation,String target,String key,Object command,Class<T> type) {
        key(key);var row=mapper.receipt(actor,operation,target,key);if(row==null) return null;
        if(!fingerprint(command).equals(row.getFingerprint())) throw problem(org.springframework.http.HttpStatus.CONFLICT,"people-idempotency-conflict");
        if(row.getStatus()==202) return null;
        var event=mapper.event(row.getEventId());
        if(event==null || !row.getId().equals(event.getReceiptId())) throw unavailable();
        T value=decode(row.getResultJson(),type);
        if(value instanceof cn.jualn.miniapp.module.person.bo.QualificationBO.Mentor prior
                && (prior.personStatus()==null || prior.bindingState()==null || prior.accountAvailability()==null)) throw unavailable();
        if(value instanceof cn.jualn.miniapp.module.person.bo.QualificationBO.Import prior) {
            var current=qualifications.rosterImport(prior.importId());if(current==null) throw unavailable();
            value=type.cast(new cn.jualn.miniapp.module.person.bo.QualificationBO.Import(prior.importId(),prior.organizationId(),prior.sourceReference(),prior.state(),
                prior.columns(),prior.version(),prior.expiresAt(),prior.rowCount(),prior.invalidRowCount(),prior.importedCount(),prior.mapping(),current.getNamespace(),
                current.getWorkbook()==null || !current.getFileRetainUntil().isAfter(mapper.now())?"PURGED":"AVAILABLE",PersonnelValues.instant(current.getFileRetainUntil()),
                prior.planId(),prior.planExpiresAt()));
        }
        return new Resource<>(value,row.getEtag(),row.getLocation(),row.getStatus());
    }
    public <T> Resource<T> append(long actor,String operation,String target,String key,Object command,String eventId,String aggregate,
                           long version,Object before,T after,String reason,String reference,String method,String source,
                           Long sourceVersion,Long approver,LocalDateTime at,int status,String location) {
        return append(actor,operation,target,key,command,eventId,aggregate,version,before,after,reason,reference,method,source,sourceVersion,approver,at,status,location,null,null,null);
    }
    public <T> Resource<T> append(long actor,String operation,String target,String key,Object command,String eventId,String aggregate,
                           long version,Object before,T after,String reason,String reference,String method,String source,
                           Long sourceVersion,Long approver,LocalDateTime at,int status,String location,
                           String localOrg,Object localBefore,Object localAfter) {
        key(key);var e=new PersonRows.Event();e.setId(eventId);e.setAggregateType(aggregate);e.setAggregateId(after instanceof cn.jualn.miniapp.module.person.bo.QualificationBO.Application application?application.applicationId():after instanceof cn.jualn.miniapp.module.person.bo.QualificationBO.Mentor mentor?mentor.personId():target);
        if("PERSON".equals(aggregate) || "MENTOR".equals(aggregate)) e.setPersonId(e.getAggregateId());
        e.setAggregateVersion(version);e.setOperation(operation);e.setBeforeJson(before==null?null:encode(before));e.setAfterJson(encode(after));
        if(command instanceof cn.jualn.miniapp.module.person.bo.QualificationBO.QualificationChange change) e.setOperation(change.action());
        e.setOperatorUserId(actor);e.setRecordedAt(at);e.setReason(reason);e.setVerificationReference(reference);
        e.setSource("PERSON".equals(aggregate) && "ROSTER_IMPORT".equals(method)?"ROSTER_IMPORT":"ROSTER_EXACT_MATCH".equals(method)?method:"ADMIN_VERIFIED_SOURCE");
        e.setVerificationMethod(method);e.setSourceReference(source);e.setSourceVersion(sourceVersion);e.setSourceApprover(approver);
        var pending="createPartyRosterImport".equals(operation)?mapper.receipt(actor,operation,((cn.jualn.miniapp.module.person.bo.QualificationBO.Import)after).organizationId(),key):null;
        if(pending!=null && (pending.getStatus()!=202 || !pending.getFingerprint().equals(fingerprint(command)))) throw unavailable();
        e.setReceiptId(pending==null?id():pending.getId());e.setTraceId(org.slf4j.MDC.get("traceId"));
        if(localOrg!=null) {e.setOperation("LOCAL_MEMBERSHIP_CHANGE");e.setLocalOrganizationId(localOrg);e.setLocalBeforeJson(encode(localBefore));e.setLocalAfterJson(encode(localAfter));}
        one(mapper.insertEvent(e));
        managementAudit.personFact(eventId,e.getReceiptId(),actor,operation,aggregate,target,e.getPersonId(),e.getBeforeJson(),e.getAfterJson(),at);
        var r=new PersonRows.Receipt();r.setId(e.getReceiptId());r.setActor(actor);r.setOperation(operation);
        r.setTarget("createPartyRosterImport".equals(operation)?((cn.jualn.miniapp.module.person.bo.QualificationBO.Import)after).organizationId():target);r.setCommandKey(key);
        r.setFingerprint(fingerprint(command));r.setResultJson(encode(localAfter==null?after:JSON.valueToTree(localAfter).get("fact")));
        r.setEtag(etag(version));r.setLocation(location);r.setStatus(status);r.setEventId(eventId);r.setCreatedAt(at);one(pending==null?mapper.insertReceipt(r):qualifications.completeUploadReceipt(r));
        return new Resource<>(after,r.getEtag(),location,status);
    }
}
