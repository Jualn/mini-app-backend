package cn.jualn.miniapp.module.person.service.roster;

import static cn.jualn.miniapp.module.person.service.support.PersonnelReads.*;

import cn.jualn.miniapp.module.person.service.facts.PersonFoundationService;
import cn.jualn.miniapp.module.person.service.facts.PersonFactReaders;
import cn.jualn.miniapp.module.person.service.facts.PersonReadContext;
import cn.jualn.miniapp.module.person.service.facts.PersonProjection;
import cn.jualn.miniapp.module.person.service.facts.PersonCatalog;
import cn.jualn.miniapp.module.person.service.access.PersonAuthorityGate.Access;
import cn.jualn.miniapp.module.person.service.profile.ManagedPersonService;
import cn.jualn.miniapp.module.person.service.profile.ManagedPersonIdentifierService;
import cn.jualn.miniapp.module.person.service.profile.ManagedPersonAccess;
import cn.jualn.miniapp.module.person.service.binding.ManagedPersonBindingService;
import cn.jualn.miniapp.module.person.service.support.PersonnelJournal;
import cn.jualn.miniapp.module.person.service.support.PersonnelValues;
import cn.jualn.miniapp.module.person.service.support.PersonPrivateData;

import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import cn.jualn.miniapp.module.person.bo.PersonBO;
import cn.jualn.miniapp.module.person.bo.QualificationBO;
import cn.jualn.miniapp.module.person.bo.QualificationBO.Application;
import cn.jualn.miniapp.module.person.bo.QualificationBO.Apply;
import cn.jualn.miniapp.module.person.bo.QualificationBO.Audit;
import cn.jualn.miniapp.module.person.bo.QualificationBO.Column;
import cn.jualn.miniapp.module.person.bo.QualificationBO.Commit;
import cn.jualn.miniapp.module.person.bo.QualificationBO.Correction;
import cn.jualn.miniapp.module.person.bo.QualificationBO.CreationRecovery;
import cn.jualn.miniapp.module.person.bo.QualificationBO.Entry;
import cn.jualn.miniapp.module.person.bo.QualificationBO.EntryQuery;
import cn.jualn.miniapp.module.person.bo.QualificationBO.HeaderColumns;
import cn.jualn.miniapp.module.person.bo.QualificationBO.Import;
import cn.jualn.miniapp.module.person.bo.QualificationBO.Mapping;
import cn.jualn.miniapp.module.person.bo.QualificationBO.MyIdentity;
import cn.jualn.miniapp.module.person.bo.QualificationBO.Page;
import cn.jualn.miniapp.module.person.bo.QualificationBO.ParsedRow;
import cn.jualn.miniapp.module.person.bo.QualificationBO.PreviewPage;
import cn.jualn.miniapp.module.person.bo.QualificationBO.PreviewRow;
import cn.jualn.miniapp.module.person.bo.QualificationBO.PrivateValues;
import cn.jualn.miniapp.module.person.bo.QualificationBO.Resolution;
import cn.jualn.miniapp.module.person.bo.QualificationBO.Resource;
import cn.jualn.miniapp.module.person.bo.QualificationBO.RowError;
import cn.jualn.miniapp.module.person.bo.QualificationBO.WorkbookSheets;

import cn.jualn.miniapp.module.person.entity.QualificationRows;
import cn.jualn.miniapp.module.person.mapper.ManagedPersonMapper;
import cn.jualn.miniapp.module.person.mapper.PersonMapper;
import cn.jualn.miniapp.module.person.mapper.QualificationMapper;

import cn.jualn.miniapp.module.authorization.service.TransactionalAuthorization;
import com.fasterxml.jackson.core.type.TypeReference;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static cn.jualn.miniapp.module.person.service.support.PersonnelValues.tree;
import static cn.jualn.miniapp.module.person.service.support.PersonnelValues.conflict;
import static cn.jualn.miniapp.module.person.service.support.PersonnelValues.decode;
import static cn.jualn.miniapp.module.person.service.support.PersonnelValues.encode;
import static cn.jualn.miniapp.module.person.service.support.PersonnelValues.etag;
import static cn.jualn.miniapp.module.person.service.support.PersonnelValues.id;
import static cn.jualn.miniapp.module.person.service.support.PersonnelValues.instant;
import static cn.jualn.miniapp.module.person.service.support.PersonnelValues.invalid;
import static cn.jualn.miniapp.module.person.service.support.PersonnelValues.key;
import static cn.jualn.miniapp.module.person.service.support.PersonnelValues.mask;
import static cn.jualn.miniapp.module.person.service.support.PersonnelValues.match;
import static cn.jualn.miniapp.module.person.service.support.PersonnelValues.one;
import static cn.jualn.miniapp.module.person.service.support.PersonnelValues.page;
import static cn.jualn.miniapp.module.person.service.support.PersonnelValues.problem;
import static cn.jualn.miniapp.module.person.service.support.PersonnelValues.reason;
import static cn.jualn.miniapp.module.person.service.support.PersonnelValues.text;
import static cn.jualn.miniapp.module.person.service.support.PersonnelValues.unavailable;

@Service
@RequiredArgsConstructor
@Transactional(isolation = Isolation.REPEATABLE_READ)
public class PartyRosterService {
    private final PartyPersonnelCorrections corrections;
    private final QualificationMapper mapper;
    private final PersonMapper facts;
    private final PersonFactReaders readers;
    private final PersonFoundationService people;
    private final TransactionalAuthorization accounts;
    private final RosterAccess access;
    private final PersonPrivateData secrets;
    private final RosterWorkbook excel;
    private final PersonnelJournal journal;
    private final PersonCatalog catalog;
    private final ManagedPersonMapper managed;
    private final ManagedPersonService profiles;
    private final ManagedPersonIdentifierService identifiers;
    private final ManagedPersonBindingService bindings;
    private final ManagedPersonAccess personAccess;

    private final RosterUploadService uploads;
    private final cn.jualn.miniapp.module.person.service.access.PersonBusinessScopes businessScopes;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Resource<Import> upload(long actor, String organization, String source, String filename, String mime, byte[] bytes, String key) {
        return uploads.upload(actor, organization, source, filename, mime, bytes, key);
    }

    public Resource<Import> getImport(long actor, String id) {
        var c = readers.open(Access.EXCLUSIVE, actor);
        var row = owned(actor, id, false, c.evaluatedAt());
        restoreMissingPlan(actor, row, c);
        return resource(project(row, c.evaluatedAt()), row.getRevision(), 200, null);
    }

    public Page<Import> imports(long actor, int page, int size, String state) {
        page(page, size);
        if (state != null && !Set.of("UPLOADED", "PREVIEW_READY", "COMMITTED", "EXPIRED").contains(state))
            throw invalid("state");
        var c = readers.open(Access.EXCLUSIVE, actor);
        access.capabilities(actor, false, c.evaluatedAt());
        var allowed = new ArrayList<Import>();
        for (var row : mapper.imports(actor)) {
            if (!access.organization(actor, row.getOrganizationId(), false, c.evaluatedAt())) continue;
            if (mapper.importCorruption(row.getId()) != 0) throw unavailable();
            if (state == null || state.equals(row.getState())) restoreMissingPlan(actor, row, c);
            var view = project(row, c.evaluatedAt());
            if (state == null || state.equals(view.state())) allowed.add(view);
        }
        return paginate(allowed, page, size, c.evaluatedAt());
    }

    public CreationRecovery creationReceipt(long actor, String organization, String key) {
        key(key);
        var c = readers.open(actor);
        access.requireOrganization(actor, organization, false, c.evaluatedAt());
        var receipt = facts.receipt(actor, "createPartyRosterImport", organization, key);
        if (receipt == null) return new CreationRecovery("NOT_FOUND", null);
        if (receipt.getStatus() == 202) return new CreationRecovery("IN_PROGRESS", null);
        if (receipt.getStatus() != 201 || facts.event(receipt.getEventId()) == null) throw unavailable();
        var result = decode(receipt.getResultJson(), Import.class);
        if (!organization.equals(result.organizationId())) throw unavailable();
        return new CreationRecovery("SUCCEEDED", result.importId());
    }

    public MyIdentity myIdentity(long user) {
        var c=readers.open(user);readableAccount(user);var binding=managed.accountBinding(user);var at=c.evaluatedAt();
        if (binding != null) cn.jualn.miniapp.module.person.service.binding.PersonBindingTrust.require(binding);
        if(binding==null) return new MyIdentity(Long.toString(user),null,null,null,"UNBOUND",instant(at),null,null,null,null);
        var profile=managed.profile(binding.getPersonId());if(profile==null) throw unavailable();
        String person=binding.getPersonId();var h=facts.head(person);
        if(!"ACTIVE".equals(profile.getStatus())) return new MyIdentity(Long.toString(user),null,null,null,"PERSON_UNAVAILABLE",instant(at),null,null,person,binding.getId());
        if(h==null || "UNKNOWN".equals(h.getIdentityVerification())) return new MyIdentity(Long.toString(user),"UNKNOWN",null,null,"UNKNOWN",instant(at),null,null,person,binding.getId());
        if("DISPUTED".equals(h.getIdentityVerification())) return new MyIdentity(Long.toString(user),"DISPUTED",null,null,"DISPUTED",instant(at),null,null,person,binding.getId());
        if(!"VERIFIED".equals(h.getIdentityVerification()) || h.getIdentityChangeId()==null || h.getIdentityVerifiedAt()==null) throw unavailable();
        var event=facts.event(h.getIdentityChangeId());if(event==null) throw unavailable();
        String method=event.getVerificationMethod()==null?"ADMIN_MANUAL":event.getVerificationMethod();
        var identity=PersonProjection.identity(person,h);String status;
        if("NONE".equals(identity.presence())) status="NONE";
        else {var r=identity.currentRecord();if(r==null || !catalog.confirmed(r.stageId()) || r.validFrom()==null) throw unavailable();status=r.status();
            if(r.validFrom().isAfter(at)) status="NOT_YET_VALID";
            else if("ACTIVE".equals(status) && r.validUntil()!=null && !r.validUntil().isAfter(at)) status="EXPIRED";}
        return new MyIdentity(Long.toString(user),"VERIFIED",identity.presence(),identity.currentRecord(),status,instant(at),instant(h.getIdentityVerifiedAt()),method,person,binding.getId());
    }
    private void readableAccount(long user) {
        if (!accounts.currentNow(user).accountEligible()) throw problem(HttpStatus.FORBIDDEN, "forbidden");
    }

    public Resource<Import> map(long actor, String id, Mapping command, String key, String match) {
        var c = readers.open(cn.jualn.miniapp.module.person.service.access.PersonAuthorityGate.Access.EXCLUSIVE, actor);
        var row = owned(actor, id, true, c.evaluatedAt());
        var replay = journal.replay(actor, "mapPartyRosterImport", id, key, command, Import.class);
        if (replay != null) {
            if (replay.value().planId() != null) return replay;
            if (!replay.value().version().equals(Long.toString(row.getRevision())))
                throw problem(HttpStatus.PRECONDITION_FAILED, "precondition-failed");
            restoreMissingPlan(actor, row, c);
            return resource(project(row, c.evaluatedAt()), row.getRevision(), 200, null);
        }
        match(match, row.getRevision());
        editable(row, c.evaluatedAt(), false);
        Object before = currentMapping(row) ? project(row, c.evaluatedAt()) : decode(facts.event(row.getLastEventId()).getAfterJson(),com.fasterxml.jackson.databind.JsonNode.class);
        var parsed = excel.parse(workbook(row,c.evaluatedAt()), command, catalog);
        validateDuplicates(parsed);
        row.setMappingJson(encode(command));
        row.setPreview(encrypt("preview", parsed));
        row.setState("PREVIEW_READY");
        row.setRevision(row.getRevision() + 1);
        row.setRowCount(parsed.size());
        row.setInvalidRowCount((int) parsed.stream().filter(r -> !r.errors().isEmpty()).count());
        row.setLastEventId(id());
        assignPlan(row, planFingerprint(actor, row, parsed, c), c.evaluatedAt());
        var result = journal.append(actor, "mapPartyRosterImport", id, key, command, row.getLastEventId(), "ROSTER_IMPORT", row.getRevision(), before, project(row, c.evaluatedAt()),
                "Map roster source", row.getSourceReference(), "ADMIN_VERIFIED_SOURCE", row.getSourceReference(), row.getRevision(), actor, c.evaluatedAt(), 200, null);
        // last_event_id references the event written by journal.append.
        one(mapper.saveImport(row));
        one(mapper.savePlan(row));
        finish(actor, row, true);
        return result;
    }

    public Resource<WorkbookSheets> sheets(long actor,String id) {
        var c=readers.open(actor);var row=owned(actor,id,false,c.evaluatedAt());
        var bytes=workbook(row,c.evaluatedAt());
        var view=new WorkbookSheets(id,Long.toString(row.getRevision()),sourceRead(()->excel.sheets(bytes)));
        return resource(view,row.getRevision(),200,null);
    }
    public Resource<HeaderColumns> columns(long actor,String id,int sheet,int header) {
        var c=readers.open(actor);var row=owned(actor,id,false,c.evaluatedAt());
        var bytes=workbook(row,c.evaluatedAt());
        var view=new HeaderColumns(id,Long.toString(row.getRevision()),sheet,header,sourceRead(()->excel.headers(bytes,sheet,header)));
        return resource(view,row.getRevision(),200,null);
    }
    private byte[] workbook(QualificationRows.Import row,LocalDateTime at) {
        if(row.getWorkbook()==null || !row.getFileRetainUntil().isAfter(at))
            throw problem(HttpStatus.GONE,"roster-file-unavailable");
        return secrets.decrypt("workbook",row.getWorkbook());
    }
    private static <T> T sourceRead(java.util.function.Supplier<T> read) {
        try {return read.get();}
        catch(cn.jualn.miniapp.common.exception.ContractProblemException failure) {
            if(failure.getStatus()==HttpStatus.UNPROCESSABLE_ENTITY) throw unavailable();
            throw failure;
        }
    }
    public Resource<PreviewPage> rows(long actor,String id,int page,int size) {
        return rows(actor, id, page, size, "INCREMENTAL");
    }
    public Resource<PreviewPage> rows(long actor,String id,int page,int size,String mode) {
        if (!Set.of("INCREMENTAL", "FULL_REVIEW").contains(mode)) throw invalid("mode");
        boolean full = "FULL_REVIEW".equals(mode);
        page(page,size);var c=readers.open(cn.jualn.miniapp.module.person.service.access.PersonAuthorityGate.Access.EXCLUSIVE,actor);var row=owned(actor,id,false,c.evaluatedAt());
        workbook(row,c.evaluatedAt());
        if(!Set.of("PREVIEW_READY","COMMITTED").contains(row.getState()) || full && "COMMITTED".equals(row.getState()))
            throw conflict();
        if(!"COMMITTED".equals(row.getState()) && !row.getExpiresAt().isAfter(c.evaluatedAt())) throw problem(HttpStatus.GONE,"roster-import-expired");
        var parsed=parsed(row);validateDuplicates(parsed);var mapping=decode(row.getMappingJson(),Mapping.class);
        updatePlan(actor, row, parsed, c, full);
        var reverse = full ? paginate(missingMembers(actor, row, parsed, c.evaluatedAt()).stream()
                .map(member -> new QualificationBO.MissingMember(member.membershipId(), member.realName())).toList(),
                page, size, c.evaluatedAt()) : null;
        var review = reverse == null ? null : new QualificationBO.RosterReviewPage(reverse.items(), reverse.totalItems());
        var pageOfInputs = paginate(parsed, page, size, c.evaluatedAt());
        var values=pageOfInputs.items().stream().map(r->{
            var resolved=classify(actor,row,r,c.evaluatedAt());var errors=new ArrayList<>(r.errors());
            if(errors.isEmpty() && "BLOCKED".equals(resolved.view().outcome())) errors.add(new RowError("row","CONFLICT",null,
                "PERSON_AUTHORIZED".equals(resolved.view().visibility())?resolved.reason():null,null));
            return new PreviewRow(r.rowNumber(),safe(r.values().realName(),100),r.values().studentNumber(),
                    safe(r.values().majorClass(),200),r.stageId(),errors,resolved.view(), workbookMatch(actor, row.getOrganizationId(), r, resolved, c));
        }).toList();
        return resource(new PreviewPage(values,page,size,pageOfInputs.totalItems(),instant(c.evaluatedAt()),id,Long.toString(row.getRevision()),mapping.sheetIndex(),mapping.headerRow(),row.getPlanId(),instant(row.getPlanExpiresAt()),review),row.getRevision(),200,null);
    }
    private void restoreMissingPlan(long actor, QualificationRows.Import row, PersonReadContext context) {
        if ("PREVIEW_READY".equals(row.getState()) && row.getExpiresAt().isAfter(context.evaluatedAt())
                && (row.getPlanId() == null || row.getPlanExpiresAt() == null)) {
            updatePlan(actor, row, parsed(row), context);
        }
    }

    private void updatePlan(long actor, QualificationRows.Import row, List<ParsedRow> parsed, PersonReadContext context) {
        updatePlan(actor, row, parsed, context, false);
    }
    private void updatePlan(long actor, QualificationRows.Import row, List<ParsedRow> parsed, PersonReadContext context, boolean full) {
        String fingerprint = planFingerprint(actor, row, parsed, context, full);
        if (row.getPlanId() == null || !fingerprint.equals(row.getPlanFingerprint())
                || row.getPlanExpiresAt() == null || !row.getPlanExpiresAt().isAfter(context.evaluatedAt())) {
            assignPlan(row, fingerprint, context.evaluatedAt());
            one(mapper.savePlan(row));
        }
    }
    private static void assignPlan(QualificationRows.Import row, String fingerprint, LocalDateTime at) {
        row.setPlanId(id());
        row.setPlanFingerprint(fingerprint);
        var expires = at.plusMinutes(10);
        row.setPlanExpiresAt(expires.isBefore(row.getExpiresAt()) ? expires : row.getExpiresAt());
    }
    private String planFingerprint(long actor,QualificationRows.Import source,List<ParsedRow> parsed,PersonReadContext context) {
        return planFingerprint(actor, source, parsed, context, false);
    }
    private String planFingerprint(long actor,QualificationRows.Import source,List<ParsedRow> parsed,PersonReadContext context, boolean full) {
        var dependencies=new ArrayList<Object>();
        if (full) dependencies.add(List.of("FULL_REVIEW", businessScopes.membershipVersion(source.getOrganizationId(), context.evaluatedAt())));
        for(var row:parsed) {
            String person=null;try {if(row.errors().isEmpty()) person=identifiers.rosterPerson(row.values().studentNumber());}
            catch(cn.jualn.miniapp.common.exception.ContractProblemException e) {if(e.getStatus()!=HttpStatus.CONFLICT) throw e;}
            if(person==null) dependencies.add(List.of(row.rowNumber(),row.errors(),"NO_CURRENT_PERSON"));
            else {var profile=managed.profile(person);var head=facts.head(person);
                dependencies.add(List.of(row.rowNumber(),person,profile.getRevision(),profile.getBindingRevision(),head==null?0:head.getRevision(),facts.memberships(person).toString()));}
        }
        for(var input:parsed) {
            dependencies.add(resolution(actor,source,input,context.evaluatedAt()));
            if(input.errors().isEmpty()) dependencies.add(mapper.byStudent(digest(input.values().studentNumber())).stream()
                .map(e->List.of(e.getId(),e.getRevision())).toList());
        }
        var org=facts.organization(source.getOrganizationId());
        return secrets.digest("roster-dependency-plan",encode(List.of(actor,source.getId(),source.getRevision(),source.getFileFingerprint(),source.getNamespace(),identifiers.rosterNamespace(),org.getRevision(),org.getType(),context.authorityVersion(),accounts.current(actor,context.evaluatedAt()).authorizationVersion(),dependencies)));
    }
    private record ClassifiedRow(Resolution view,String reason) {}

    private QualificationBO.PersonnelMatch personnelMatch(ParsedRow input, Resolution resolution, PersonReadContext context) {
        return personnelMatch(input, resolution, null, context);
    }

    QualificationBO.PersonnelMatch personnelMatch(ParsedRow input, Resolution resolution, String selected, PersonReadContext context) {
        if ("BLOCKED".equals(resolution.outcome()))
            return new QualificationBO.PersonnelMatch("UNRESOLVED", null, input.errors().isEmpty() ? "UNRESOLVED" : "INVALID_ROW");
        var person = registrationPerson(input.values().studentNumber(), selected);
        if (person == null) return new QualificationBO.PersonnelMatch("CREATE", null, null);
        boolean member = "KEEP".equals(resolution.membershipAction());
        var outcome = member ? "ALREADY_MEMBER" : "REUSE";
        if (!member && !"PERSON_AUTHORIZED".equals(resolution.visibility()))
            return new QualificationBO.PersonnelMatch(outcome, null, null);

        var profile = managed.profile(person);
        var registration = managed.registration(person);
        var identity = readers.identity(person, context);
        var current = new java.util.LinkedHashMap<String, Object>();
        current.put("realName", profile.getRealName());
        var majorClass = registration == null ? null : registration.getMajorClass();
        current.put("majorClass", majorClass);
        if ("ACTIVE".equals(identity.status())) current.put("stageId", identity.stageId());
        boolean differs = input.stageId() != null && !java.util.Objects.equals(input.stageId(), current.get("stageId"))
                || input.values().majorClass() != null && !input.values().majorClass().equals(majorClass);
        return new QualificationBO.PersonnelMatch(differs ? "DIFFERS" : outcome, current, null);
    }
    private QualificationBO.PersonnelMatch workbookMatch(long actor, String organization, ParsedRow input,
                                                         ClassifiedRow classification, PersonReadContext context) {
        var view = classification.view();
        var match = personnelMatch(input, view, context);
        if (!input.errors().isEmpty()) return match;
        if ("BLOCKED".equals(view.outcome())
                && !Set.of("NAME_MISMATCH", "POTENTIAL_DUPLICATE").contains(
                        classification.reason() == null ? "" : classification.reason())) return match;

        var person = identifiers.rosterPerson(input.values().studentNumber());
        if (person != null) {
            var editable = corrections.editable(actor, organization, person, context);
            if (editable.isEmpty()) return match;
            if ("NAME_MISMATCH".equals(classification.reason())) {
                return new QualificationBO.PersonnelMatch("DIFFERS", Map.of("realName", managed.profile(person).getRealName()),
                        null, person, editable, null, currentRegisteredCycle(organization, person, context.evaluatedAt()));
            }
            return new QualificationBO.PersonnelMatch(match.outcome(), match.current(), match.issue(), person, editable, null, currentRegisteredCycle(organization, person, context.evaluatedAt()));
        }
        if (!"POTENTIAL_DUPLICATE".equals(classification.reason())) return match;
        var candidates = namedCandidates(actor, input.values().realName(), context.evaluatedAt()).stream().limit(20)
                .filter(candidate -> corrections.editable(actor, organization, candidate.personId(), context).contains("studentNumber"))
                .map(candidate -> new QualificationBO.PersonnelCandidate(candidate.personId(), candidate.realName(),
                        currentRegisteredCycle(organization, candidate.personId(), context.evaluatedAt()))).toList();
        return candidates.isEmpty() ? match
                : new QualificationBO.PersonnelMatch("UNRESOLVED", null, match.issue(), null, null, candidates, null);
    }

    private Resolution resolution(long actor,QualificationRows.Import source,ParsedRow input,LocalDateTime at) {return classify(actor,source,input,at).view();}
    private ClassifiedRow classify(long actor,QualificationRows.Import source,ParsedRow input,LocalDateTime at) {
        var result = classify(actor, source.getOrganizationId(), source.getNamespace(), input, null, at);
        var view = result.view();
        if ("READY".equals(view.outcome()) && registrationPerson(input.values().studentNumber(), null) == null) {
            var candidates = namedCandidates(actor, input.values().realName(), at);
            if (candidates.size() > 20 || candidates.stream().anyMatch(candidate -> !hasTrustedNumber(candidate.personId())))
                return new ClassifiedRow(new Resolution(view.visibility(), "BLOCKED", "BLOCKED", null, null, "BLOCKED"), "POTENTIAL_DUPLICATE");
        }
        return result;
    }

    Resolution matchRegistration(long actor, String organization, ParsedRow input, String selectedPerson, LocalDateTime at) {
        return classify(actor, organization, identifiers.rosterNamespace(), input, selectedPerson, at).view();
    }

    private ClassifiedRow classify(long actor, String organization, String namespace, ParsedRow input, String selectedPerson, LocalDateTime at) {
        boolean directory=personAccess.optionalDirectoryRead(actor,at);
        String person=selectedPerson;boolean blocked=!input.errors().isEmpty() || !java.util.Objects.equals(namespace,identifiers.rosterNamespace());String reason=null;
        try {if(!blocked && input.values().studentNumber()!=null) {
            var numbered=identifiers.rosterPerson(input.values().studentNumber());
            if (selectedPerson!=null && !selectedPerson.equals(numbered)) { blocked=true; reason="IDENTIFIER_CONFLICT"; }
            else person=numbered;
        }}
        catch(cn.jualn.miniapp.common.exception.ContractProblemException e) {if(e.getStatus()!=HttpStatus.CONFLICT) throw e;blocked=true;reason="IDENTIFIER_CONFLICT";}
        boolean readable=directory;
        if(person!=null && !readable) readable=personAccess.optionalRead(actor,person,at);
        boolean present=person!=null && facts.memberships(person).stream().anyMatch(m->organization.equals(m.getOrganizationId()) && !m.getValidFrom().isAfter(at) && (m.getValidUntil()==null || m.getValidUntil().isAfter(at)));
        boolean sourceExists=false;
        if(person!=null) {
            if(!input.values().realName().equals(managed.profile(person).getRealName())) {blocked=true;reason="NAME_MISMATCH";}
            if(managed.everBound(person,actor)) blocked=true;
            if(!present && facts.memberships(person).stream().anyMatch(m->organization.equals(m.getOrganizationId()) && m.getValidFrom().isAfter(at))) {blocked=true;reason="LOCAL_MEMBERSHIP_CONFLICT";}
            for(var previous:input.values().studentNumber() == null ? List.<QualificationRows.Entry>of() : mapper.byStudent(digest(input.values().studentNumber()))) {
                if(!organization.equals(previous.getOrganizationId())) continue;
                sourceExists=true;
                values(previous);
                if(!person.equals(previous.getPersonId())) {blocked=true;if(reason==null) reason="SOURCE_CONFLICT";}
            }
        }
        return new ClassifiedRow(new Resolution(readable?"PERSON_AUTHORIZED":"LOCAL_ONLY",blocked?"BLOCKED":"READY",blocked?"BLOCKED":present?"KEEP":"ADD",readable?(blocked?"BLOCKED":person==null?"CREATE":"REUSE"):null,readable?person:null,blocked?"BLOCKED":sourceExists?"KEEP":"CREATE"),reason);
    }
    private record MissingMember(String personId, String membershipId, String realName) {}

    private List<MissingMember> missingMembers(long actor, QualificationRows.Import source, List<ParsedRow> rows, LocalDateTime at) {
        return missingMembers(actor, source, rows, at, Map.of());
    }

    private List<MissingMember> missingMembers(long actor, QualificationRows.Import source, List<ParsedRow> rows,
                                               LocalDateTime at, Map<Integer, WorkbookCorrection> changes) {
        var matched = new java.util.HashSet<String>();
        for (var input : rows) {
            var correction = changes.get(input.rowNumber());
            if (correction != null) matched.add(correction.person());
            else if ("KEEP".equals(resolution(actor, source, input, at).membershipAction()))
                matched.add(identifiers.rosterPerson(input.values().studentNumber()));
        }
        return businessScopes.registeredRoster(source.getOrganizationId(), at).stream()
                .filter(member -> !matched.contains(member.personId()))
                .map(member -> new MissingMember(member.personId(), member.membershipId(),
                        managed.profile(member.personId()).getRealName()))
                .toList();
    }

    private java.util.Map<String, QualificationBO.MissingMemberDecision> reviewDecisions(
            QualificationBO.ReviewDecision review, List<MissingMember> reverse) {
        var decisions = new java.util.HashMap<String, QualificationBO.MissingMemberDecision>();
        if (review == null) return decisions;
        if (review.decisions() == null || review.decisions().size() > 10000) throw invalid("review.decisions");
        var cycles = reverse.stream().map(MissingMember::membershipId).collect(java.util.stream.Collectors.toSet());
        for (var decision : review.decisions()) {
            if (decision == null || decision.action() == null || !cycles.contains(decision.membershipId())
                    || !Set.of("RETAIN", "REMOVE", "DEFER").contains(decision.action())
                    || decisions.putIfAbsent(decision.membershipId(), decision) != null) throw invalid("review.decisions");
            if ("RETAIN".equals(decision.action()) || decision.basis() != null) text(decision.basis(), 256, "review.basis");
        }
        return decisions;
    }

    private void requireExactReviewedSet(String organization, Set<String> expectedPeople,
                                         java.util.Map<String, String> retainedCycles, LocalDateTime at) {
        var current = businessScopes.registeredRoster(organization, at);
        var actual = current.stream().map(member -> member.personId()).collect(java.util.stream.Collectors.toSet());
        if (current.size() != actual.size() || !actual.equals(expectedPeople) || current.stream().anyMatch(member ->
                retainedCycles.containsKey(member.personId()) && !retainedCycles.get(member.personId()).equals(member.membershipId())))
            throw problem(HttpStatus.CONFLICT, "person-plan-stale");
    }

    record WorkbookCorrection(String person, Map<String, String> fields) {}
    record WorkbookPreview(String fingerprint, LocalDateTime expires, String etag,
                           List<PartyPersonnelPlans.Effect> effects, Map<Integer, WorkbookCorrection> corrections) {}

    private record WorkbookSelection(List<ParsedRow> selected, Set<Integer> skipped, List<MissingMember> reverse,
                                     java.util.Map<String, QualificationBO.MissingMemberDecision> decisions) {}

    private Map<Integer, WorkbookCorrection> workbookCorrections(long actor, String organization, List<ParsedRow> parsed,
            PartyPersonnelPlans.Workbook command, PersonReadContext context) {
        var result = new HashMap<Integer, WorkbookCorrection>();
        if (command.corrections() == null) return result;
        if (command.corrections().size() > parsed.size()) throw invalid("workbook/corrections");
        var inputs = parsed.stream().collect(java.util.stream.Collectors.toMap(ParsedRow::rowNumber, input -> input));
        var people = new java.util.HashSet<String>();
        for (var decision : command.corrections()) {
            if (decision == null) throw invalid("workbook/corrections");
            var input = inputs.get(decision.rowNumber());
            if (input == null || !input.errors().isEmpty() || result.containsKey(decision.rowNumber()))
                throw invalid("workbook/corrections");
            var person = text(decision.personId(), 128, "personId");
            if (!people.add(person)) throw invalid("workbook/corrections");
            var fields = corrections.prepare(actor, organization, person, decision.fields(), context);
            var values = input.values();
            var owner = identifiers.rosterPerson(values.studentNumber());
            var profile = managed.profile(person);
            if (owner == null) {
                if (!values.studentNumber().equals(fields.get("studentNumber")) || !profile.getRealName().equals(values.realName()))
                    throw conflict();
            } else if (!owner.equals(person) || fields.containsKey("studentNumber")) {
                throw conflict();
            }
            for (var entry : mapper.byStudent(digest(values.studentNumber()))) {
                if (organization.equals(entry.getOrganizationId()) && !person.equals(entry.getPersonId())) throw conflict();
            }
            result.put(decision.rowNumber(), new WorkbookCorrection(person, fields));
        }
        return result;
    }

    WorkbookPreview previewWorkbook(long actor, String organization, PartyPersonnelPlans.Workbook command, PersonReadContext context) {
        text(command.importId(), 128, "importId");
        text(command.parsedPlanId(), 128, "parsedPlanId");
        text(command.version(), 128, "version");
        var at = context.evaluatedAt();
        var batch = owned(actor, command.importId(), true, at);
        if (!organization.equals(batch.getOrganizationId())) throw problem(HttpStatus.NOT_FOUND, "not-found");
        if (!Long.toString(batch.getRevision()).equals(command.version())) throw problem(HttpStatus.CONFLICT, "person-plan-stale");
        var parsed = parsed(batch);
        var changes = workbookCorrections(actor, organization, parsed, command, context);
        var selection = selectWorkbook(actor, batch, parsed, command.parsedPlanId(), command.skipRows(), command.review(), context, changes);
        var requiredKeeps = selection.selected().stream().filter(input -> !changes.containsKey(input.rowNumber())).filter(input ->
                "DIFFERS".equals(personnelMatch(input, resolution(actor, batch, input, at), context).outcome()))
                .map(ParsedRow::rowNumber).collect(java.util.stream.Collectors.toSet());
        var keep = command.keepCurrentRows() == null ? List.<Integer>of() : command.keepCurrentRows();
        if (keep.size() != requiredKeeps.size() || !requiredKeeps.equals(new java.util.HashSet<>(keep)))
            throw invalid("workbook/keepCurrentRows");
        var effects = new ArrayList<PartyPersonnelPlans.Effect>();
        for (var input : parsed) {
            var correction = changes.get(input.rowNumber());
            var effect = importEffect(actor, batch, input, selection.skipped().contains(input.rowNumber()), context, correction);
            effects.add(new PartyPersonnelPlans.Effect(effect.sequence(), organization, null,
                    safe(input.values().realName(), 100), effect.actions(), effect.correctedFields(),
                    correction == null ? null : correction.fields()));
        }
        int sequence = parsed.stream().mapToInt(ParsedRow::rowNumber).max().orElse(0);
        for (var member : selection.reverse()) {
            var decision = selection.decisions().get(member.membershipId());
            effects.add(new PartyPersonnelPlans.Effect(++sequence, organization, null, member.realName(),
                    List.of(reverseAction(decision == null ? "DEFER" : decision.action()))));
        }
        var digest = secrets.digest("party-workbook-preview", encode(List.of(batch.getPlanFingerprint(), command, effects)));
        return new WorkbookPreview(digest, batch.getPlanExpiresAt(), etag(batch.getRevision()), effects, changes);
    }

    private static String reverseAction(String action) {
        return switch (action) { case "RETAIN" -> "UNCHANGED"; case "REMOVE" -> "REMOVE"; default -> "SKIP"; };
    }

    private WorkbookSelection selectWorkbook(long actor, QualificationRows.Import batch, List<ParsedRow> parsed,
            String planId, List<Integer> skipRows, QualificationBO.ReviewDecision review, PersonReadContext c,
            Map<Integer, WorkbookCorrection> changes) {
        editable(batch, c.evaluatedAt(), true);
        if (!"PREVIEW_READY".equals(batch.getState()) || batch.getRowCount() < 1)
            throw problem(HttpStatus.UNPROCESSABLE_ENTITY, "roster-import-invalid");
        validateDuplicates(parsed);
        boolean full = review != null;
        var skipped = new java.util.HashSet<Integer>();
        var rowNumbers = parsed.stream().map(ParsedRow::rowNumber).collect(java.util.stream.Collectors.toSet());
        if (skipRows == null || skipRows.stream().anyMatch(number -> !rowNumbers.contains(number) || !skipped.add(number)))
            throw invalid("skipRows");
        var selected = parsed.stream().filter(input -> !skipped.contains(input.rowNumber())).toList();
        if ((!full && selected.isEmpty()) || selected.stream().anyMatch(input -> !input.errors().isEmpty()))
            throw problem(HttpStatus.UNPROCESSABLE_ENTITY, "roster-import-invalid");
        if (!planId.equals(batch.getPlanId()) || batch.getPlanExpiresAt() == null || !batch.getPlanExpiresAt().isAfter(c.evaluatedAt())
                || !planFingerprint(actor,batch,parsed,c,full).equals(batch.getPlanFingerprint()))
            throw problem(HttpStatus.CONFLICT, "person-plan-stale");
        if (changes.keySet().stream().anyMatch(skipped::contains)) throw invalid("workbook/corrections");
        var correctedPeople = changes.values().stream().map(WorkbookCorrection::person).collect(java.util.stream.Collectors.toSet());
        for (var input : selected) {
            if (changes.containsKey(input.rowNumber())) continue;
            if ("BLOCKED".equals(resolution(actor, batch, input, c.evaluatedAt()).outcome())) throw conflict();
            var person = identifiers.rosterPerson(input.values().studentNumber());
            if (person != null && correctedPeople.contains(person)) throw conflict();
        }
        var reverse = full ? missingMembers(actor, batch, parsed, c.evaluatedAt(), changes) : List.<MissingMember>of();
        var decisions = reviewDecisions(review, reverse);
        if (reverse.stream().anyMatch(member -> {
            var decision = decisions.get(member.membershipId());
            return decision != null && "REMOVE".equals(decision.action()) && managed.everBound(member.personId(), actor);
        })) throw problem(HttpStatus.FORBIDDEN, "person-self-governance-forbidden");
        return new WorkbookSelection(selected, skipped, reverse, decisions);
    }

    public Resource<Import> commit(long actor, String id, Commit command, String key, String match) {
        if (command != null && command.review() != null) throw invalid("review");
        var context = readers.open(cn.jualn.miniapp.module.person.service.access.PersonAuthorityGate.Access.EXCLUSIVE, actor);
        return commitInContext(actor, id, command, key, match, context, Map.of());
    }

    Resource<Import> commitInContext(long actor, String id, Commit command, String key, String match, PersonReadContext c,
                                     Map<Integer, WorkbookCorrection> changes) {
        if (command == null || !Boolean.TRUE.equals(command.confirmSource())) throw invalid("confirmSource");
        text(command.planId(),128,"planId");
        reason(command.reason(), command.verificationReference());
        c.requireUser(actor);
        var row = owned(actor, id, true, c.evaluatedAt());
        var replay = journal.replay(actor, "commitPartyRosterImport", id, key, command, Import.class);
        if (replay != null) return replay;
        match(match, row.getRevision());
        var parsed = parsed(row);
        var selection = selectWorkbook(actor, row, parsed, command.planId(), command.skipRows(), command.review(), c, changes);
        boolean full = command.review() != null;
        var skipped = selection.skipped();
        var selected = selection.selected();
        var reverse = selection.reverse();
        var decisions = selection.decisions();
        boolean complete = full && skipped.isEmpty() && reverse.stream().allMatch(member -> {
            var decision = decisions.get(member.membershipId());
            return decision != null && !"DEFER".equals(decision.action());
        });
        var expected = new java.util.HashSet<String>();
        var appliedCycles = new java.util.HashMap<String, String>();
        var retained = new java.util.HashMap<String, String>();
        var rosterBefore = complete ? people.rosterBeforeReview(actor, row.getOrganizationId(), c.evaluatedAt())
                : people.partyRosterBefore(row.getOrganizationId(), c.evaluatedAt());
        boolean unresolved = parsed.stream().filter(input -> skipped.contains(input.rowNumber()))
                .filter(input -> input.errors().isEmpty() || input.errors().stream().anyMatch(error -> "DUPLICATE".equals(error.code())))
                .anyMatch(input -> "BLOCKED".equals(resolution(actor, row, input, c.evaluatedAt()).outcome()));
        var effects = new ArrayList<>(parsed.stream().map(input -> importEffect(actor, row, input,
                skipped.contains(input.rowNumber()), c, changes.get(input.rowNumber()))).toList());
        if (complete) for (var member : businessScopes.registeredRoster(row.getOrganizationId(), c.evaluatedAt()))
            retained.put(member.personId(), member.membershipId());
        var before=project(row,c.evaluatedAt());
        for(var input:selected) {
            String source=row.getId()+":"+input.rowNumber();String childKey=secrets.digest("roster-child-command",key+":"+input.rowNumber());
            var correction = changes.get(input.rowNumber());
            String person;
            if (correction == null) {
                person = register(actor, row.getOrganizationId(), input, null, source, row.getRevision(), childKey, c);
            } else {
                person = correction.person();
                corrections.apply(actor, row.getOrganizationId(), person, correction.fields(), command.reason(),
                        command.verificationReference(), childKey, c);
            }
            expected.add(person);
            appliedCycles.put(person, currentRegisteredCycle(row.getOrganizationId(), person, c.evaluatedAt()));
            var existing=mapper.byStudent(digest(input.values().studentNumber())).stream()
                .filter(e->row.getOrganizationId().equals(e.getOrganizationId())).findFirst().orElse(null);
            if(existing!=null) {
                continue;
            }
            var entry = new QualificationRows.Entry();
            entry.setId(id());
            entry.setImportId(id);
            entry.setOrganizationId(row.getOrganizationId());
            entry.setPersonId(person);
            entry.setPrivateValues(encrypt("entry", input.values()));
            entry.setStudentDigest(digest(input.values().studentNumber()));
            entry.setExpectedStageId(input.stageId());
            entry.setBaselineStatus("ACTIVE");
            entry.setRevision(1);
            entry.setSourceReference(row.getSourceReference());
            entry.setSourceApprover(actor);
            entry.setLastEventId(id());
            journal.append(actor, "IMPORT_ENTRY", entry.getId(), key, row.getFileFingerprint(), entry.getLastEventId(), "ROSTER_ENTRY", 1, null, project(entry, c.evaluatedAt()),
                    command.reason(), command.verificationReference(), "ADMIN_VERIFIED_SOURCE", entry.getSourceReference(), row.getRevision(), actor, c.evaluatedAt(), 200, null);
            one(mapper.insertEntry(entry));
        }
        int sequence = parsed.stream().mapToInt(ParsedRow::rowNumber).max().orElse(0);
        for (var member : reverse) {
            var decision = decisions.get(member.membershipId());
            String action = decision == null ? "DEFER" : decision.action();
            if ("REMOVE".equals(action)) {
                people.removeReviewedMember(actor, row.getOrganizationId(), member.personId(), member.membershipId(),
                        command.reason(), command.verificationReference(),
                        secrets.digest("roster-review-remove", row.getId() + ":" + member.membershipId()), c);
            } else if ("RETAIN".equals(action)) expected.add(member.personId());
            effects.add(new QualificationBO.ImportEffect(++sequence, row.getOrganizationId(),
                    List.of(reverseAction(action)),
                    "ALREADY_MEMBER", action, null, new QualificationBO.ImportSource(member.realName(), null, null, null),
                    member.membershipId(), decision == null ? null : decision.basis()));
        }
        if (complete) requireExactReviewedSet(row.getOrganizationId(), expected, retained, c.evaluatedAt());
        row.setFileRetainUntil(c.evaluatedAt().plusHours(24));
        row.setState("COMMITTED");
        row.setApprovedBy(actor);
        row.setImportedCount(selected.size());
        row.setRevision(row.getRevision() + 1);
        row.setLastEventId(id());
        var totals = new QualificationBO.ImportTotals(count(effects, "CREATE"), count(effects, "REUSE"), count(effects, "CORRECT"),
                count(effects, "JOIN"), count(effects, "REMOVE"), count(effects, "UNCHANGED"), count(effects, "SKIP"));
        journal.append(actor, "recordPartyPersonnelImportResults", id, key, command, id(), "ROSTER_TRACE", 1,
                null, new QualificationBO.ImportResults(effects, 1, effects.size(), effects.size(), totals,
                        full ? complete ? "COMPLETE" : "NEEDS_REVIEW" : null),
                command.reason(), command.verificationReference(), "ADMIN_VERIFIED_SOURCE",
                row.getSourceReference(), row.getRevision(), actor, c.evaluatedAt(), 200, null);
        var result = journal.append(actor, "commitPartyRosterImport", id, key, command, row.getLastEventId(), "ROSTER_IMPORT", row.getRevision(), before, project(row, c.evaluatedAt()),
                command.reason(), command.verificationReference(), "ADMIN_VERIFIED_SOURCE", row.getSourceReference(), row.getRevision(), actor, c.evaluatedAt(), 200, null);
        one(mapper.saveImport(row));
        if (complete) people.confirmReviewedRoster(actor, rosterBefore, row.getLastEventId(),
                command.reason(), command.verificationReference(), c.evaluatedAt());
        else people.finishPartyRoster(actor, rosterBefore, row.getLastEventId(), unresolved || full, c.evaluatedAt());
        one(facts.advanceEpoch(Long.parseLong(c.authorityVersion())));
        finish(actor,row,true);
        ManagedPersonService.recheckBeforeCommit(() -> {
            var at = facts.now();
            for (var member : appliedCycles.entrySet()) {
                if (!member.getValue().equals(currentRegisteredCycle(row.getOrganizationId(), member.getKey(), at)))
                    throw problem(HttpStatus.CONFLICT, "person-plan-stale");
            }
        });
        return result;
    }

    String currentRegisteredCycle(String organization, String person, LocalDateTime at) {
        var current = facts.partyMembers(new PersonMapper.PartyMembers(organization, at, null, null, person, 2, 0));
        if (current.size() != 1) throw problem(HttpStatus.CONFLICT, "person-plan-stale");
        return current.get(0).membershipId();
    }

    String namespace() { return identifiers.rosterNamespace(); }

    String registrationPerson(String number, String selected) {
        return selected == null && number != null ? identifiers.rosterPerson(number) : selected;
    }

    List<PersonMapper.PartyNamedCandidate> namedCandidates(long actor, String name, LocalDateTime at) {
        var decision = accounts.current(actor, at);
        return facts.partyNamedCandidates(new PersonMapper.PartyNamedCandidates(RosterAccess.platform(decision),
                RosterAccess.organizationIds(decision), at, name));
    }

    boolean hasTrustedNumber(String person) {
        return managed.identifiers(person).stream().anyMatch(value -> namespace().equals(value.getNamespace())
                && "ACTIVE".equals(value.getStatus()) && "VERIFIED".equals(value.getVerification()));
    }

    String register(long actor, String organization, ParsedRow input, String selectedPerson,
                    String source, long sourceVersion, String childKey, PersonReadContext context) {
        var number = input.values().studentNumber();
        String person = registrationPerson(number, selectedPerson);
        boolean created = person == null;
        if (created) {
            person = profiles.createFromRoster(actor, input.values().realName(), input.values().majorClass(),
                    source, childKey, context).personId();
            if (number != null) identifiers.registerFromRoster(actor, person, number, source, childKey, context);
        }
        if (managed.everBound(person, actor)) throw problem(HttpStatus.FORBIDDEN, "person-self-governance-forbidden");
        people.initializeRosterPerson(actor, person, organization, input.stageId(), source, sourceVersion, actor,
                childKey, created, context);
        return person;
    }

    private QualificationBO.ImportEffect importEffect(long actor, QualificationRows.Import batch, ParsedRow row,
                                                       boolean skip, PersonReadContext context, WorkbookCorrection correction) {
        var view = resolution(actor, batch, row, context.evaluatedAt());
        var number = row.values().studentNumber();
        var source = new QualificationBO.ImportSource(safe(row.values().realName(), 100),
                number == null ? null : number.length() <= 2 ? "****" : mask(number),
                safe(row.values().majorClass(), 200), row.stageId());
        if (correction != null) return new QualificationBO.ImportEffect(row.rowNumber(), batch.getOrganizationId(),
                List.of("REUSE", "CORRECT"), "DIFFERS", "APPLY", null, source, null, null,
                List.copyOf(correction.fields().keySet()));
        if ("BLOCKED".equals(view.outcome())) {
            return new QualificationBO.ImportEffect(row.rowNumber(), batch.getOrganizationId(), List.of("SKIP"),
                    "UNRESOLVED", "SKIP", row.errors().isEmpty() ? "UNRESOLVED" : "INVALID_ROW", source);
        }
        boolean created = identifiers.rosterPerson(row.values().studentNumber()) == null;
        boolean added = "ADD".equals(view.membershipAction());
        var outcome = personnelMatch(row, view, context).outcome();
        var actions = skip ? List.of("SKIP") : List.of(created ? "CREATE" : "REUSE", added ? "JOIN" : "UNCHANGED");
        return new QualificationBO.ImportEffect(row.rowNumber(), batch.getOrganizationId(), actions,
                outcome, skip ? "SKIP" : "APPLY", null, source);
    }

    private static int count(List<QualificationBO.ImportEffect> effects, String action) {
        return Math.toIntExact(effects.stream().filter(effect -> effect.actions().contains(action)).count());
    }

    public QualificationBO.ImportResults results(long actor, String id, int page, int size) {
        page(page, size);
        var context = readers.open(actor);
        var at = context.evaluatedAt();
        access.capabilities(actor, false, at);
        var batch = mapper.rosterImport(id);
        if (batch == null || !access.organization(actor, batch.getOrganizationId(), false, at)
                || !"COMMITTED".equals(batch.getState())) throw problem(HttpStatus.NOT_FOUND, "not-found");
        var stored = mapper.importResult(id);
        if (stored == null) throw problem(HttpStatus.NOT_FOUND, "not-found");
        var results = decode(stored, QualificationBO.ImportResults.class);
        var slice = paginate(results.items(), page, size, at);
        return new QualificationBO.ImportResults(slice.items(), page, size, results.totalItems(), results.totals(), results.rosterConclusion());
    }

    public Page<QualificationBO.OrganizationImport> organizationImports(long actor, String organization, int page, int size) {
        page(page, size);
        var at = readers.open(actor).evaluatedAt();
        access.requireOrganization(actor, organization, false, at);
        long count = mapper.committedImportCount(organization);
        var rows = mapper.committedImports(organization, size, (long) (page - 1) * size).stream()
                .map(row -> new QualificationBO.OrganizationImport(row.importId(), row.sourceReference(),
                        instant(row.committedAt()), row.importedCount())).toList();
        return new Page<>(rows, page, size, count, instant(at));
    }

    public Page<Entry> entries(long actor, int page, int size, EntryQuery query) {
        page(page, size);
        validateQuery(query);
        var c = readers.open(actor);
        access.capabilities(actor, false, c.evaluatedAt());
        if (query.organizationId() != null)
            access.requireOrganization(actor, query.organizationId(), false, c.evaluatedAt());
        var authorized = new ArrayList<Entry>();
        long total = 0, start = (long) (page - 1) * size;
        var organizations = new HashMap<String, Boolean>();
        for (String after = null; ; ) {
            var batch = mapper.entriesAfter(after, 256);
            if (batch.isEmpty()) break;
            for (var row : batch) {
                if (query.organizationId() != null && !query.organizationId().equals(row.getOrganizationId())) continue;
                if (!organizations.computeIfAbsent(row.getOrganizationId(), org -> access.organization(actor, org, false, c.evaluatedAt()))
                        || !access.personWithinOrganization(actor, row, false, c.evaluatedAt())) continue;
                var value = readProjection(actor,row,c);
                if (query.organizationId() != null && !query.organizationId().equals(value.organizationId())
                        || query.linkState() != null && !query.linkState().equals("BOUND".equals(value.accountBindingState())?"LINKED":"UNBOUND".equals(value.accountBindingState())?"UNLINKED":null)
                        || query.stageId() != null && !query.stageId().equals(value.personIdentity() == null ? value.expectedStageId() : value.personIdentity().currentRecord() == null ? null : value.personIdentity().currentRecord().stageId())
                        || query.baselineStatus() != null && !query.baselineStatus().equals(value.baselineStatus())
                        || query.q() != null && !value.realName().contains(query.q().strip())
                        || query.identityVerification() != null && (value.personIdentity() == null || !query.identityVerification().equals(value.personIdentity().verification().name())))
                    continue;
                if (total >= start && authorized.size() < size) authorized.add(value);
                total++;
            }
            after = batch.get(batch.size() - 1).getId();
        }
        return new Page<>(List.copyOf(authorized), page, size, total, instant(c.evaluatedAt()));
    }

    public Resource<Entry> entry(long actor, String id) {
        var c = readers.open(actor);
        access.capabilities(actor, false, c.evaluatedAt());
        var row = entryRow(id);
        if (!access.entry(actor, row, false, c.evaluatedAt())) throw problem(HttpStatus.NOT_FOUND, "not-found");
        return resource(readProjection(actor,row,c), row.getRevision(), 200, null);
    }

    public Resource<Entry> correct(long actor, String id, Correction input, String key, String match) {
        var command = normalize(input);
        var c = readers.open(cn.jualn.miniapp.module.person.service.access.PersonAuthorityGate.Access.EXCLUSIVE, actor);
        access.capabilities(actor, true, c.evaluatedAt());
        var row = entryRow(id);
        if (!access.entry(actor, row, true, c.evaluatedAt())) throw problem(HttpStatus.NOT_FOUND, "not-found");
        var replay = journal.replay(actor, "correctPartyRosterEntry", id, key, command, Entry.class);
        if (replay != null) return replay;
        match(match, row.getRevision());
        var before = project(row, c.evaluatedAt());
        var old = values(row);
        if (!row.getPersonId().equals(identifiers.rosterPerson(command.studentNumber()))) throw conflict();
        var digest = digest(command.studentNumber());
        if ("ACTIVE".equals(command.baselineStatus()) && mapper.byStudent(digest).stream().anyMatch(e -> row.getOrganizationId().equals(e.getOrganizationId()) && !id.equals(e.getId())))
            throw conflict();
        row.setStudentDigest(digest);
        row.setPrivateValues(encrypt("entry", new PrivateValues(command.realName(), command.studentNumber(), command.majorClass())));
        row.setExpectedStageId(command.expectedStageId());
        row.setBaselineStatus(command.baselineStatus());
        row.setRevision(row.getRevision() + 1);
        row.setLastEventId(id());
        var result = journal.append(actor, "correctPartyRosterEntry", id, key, command, row.getLastEventId(), "ROSTER_ENTRY", row.getRevision(), before, project(row, c.evaluatedAt()),
                command.reason(), command.verificationReference(), "ADMIN_VERIFIED_SOURCE", row.getSourceReference(), row.getRevision(), actor, c.evaluatedAt(), 200, null);
        one(mapper.saveEntry(row));
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void beforeCommit(boolean readOnly) {
                access.capabilities(actor, true, facts.now());
                if (!access.entry(actor, row, true, facts.now())) throw problem(HttpStatus.NOT_FOUND, "not-found");
            }
        });
        return result;
    }

    public Page<Audit> history(long actor, String id, int page, int size) {
        page(page, size);
        var c = readers.open(actor);
        access.capabilities(actor, false, c.evaluatedAt());
        var row = entryRow(id);
        if (!access.entry(actor, row, false, c.evaluatedAt())) throw problem(HttpStatus.NOT_FOUND, "not-found");
        return historyPage(mapper, "ROSTER_ENTRY", id, page, size, c.evaluatedAt());
    }

    public Resource<Application> apply(long user,Apply input,String key) {
        if(input==null) throw invalid("body");
        PersonFoundationService.key(key);
        var command=new Apply(text(input.realName(),100,"realName"),text(input.studentNumber(),100,"studentNumber"));
        var c=readers.open(cn.jualn.miniapp.module.person.service.access.PersonAuthorityGate.Access.EXCLUSIVE,user);
        readableAccount(user);String target=Long.toString(user),op="createMyPartyIdentityApplication";
        var replay=journal.replay(user,op,target,key,command,Application.class);if(replay!=null) return replay;
        String person;
        try {person=identifiers.rosterPerson(command.studentNumber());}
        catch(cn.jualn.miniapp.common.exception.ContractProblemException e) {if(e.getStatus()!=HttpStatus.CONFLICT) throw e;throw conflict();}
        if(person==null || !command.realName().equals(managed.profile(person).getRealName())) throw conflict();
        var accountBinding=managed.accountBinding(user);var personBinding=managed.personBinding(person);
        if(accountBinding!=null && !person.equals(accountBinding.getPersonId()) || personBinding!=null && personBinding.getUserId()!=user) throw conflict();
        if(accountBinding!=null) {
            if(personBinding==null || !accountBinding.getId().equals(personBinding.getId()) || !cn.jualn.miniapp.module.person.service.binding.PersonBindingTrust.trusted(accountBinding)) throw unavailable();
            var existing=mapper.applicationForBinding(accountBinding.getId());
            if(existing!=null) {
                var result=journal.append(user,op,target,key,command,id(),"PARTY_APPLICATION",mapper.applicationEventVersion(existing.getId())+1,null,project(existing),
                    "Existing current binding matched",existing.getId(),"ROSTER_EXACT_MATCH",existing.getId(),1L,user,c.evaluatedAt(),201,null);
                ManagedPersonService.recheckBeforeCommit(()->readableAccount(user));return result;
            }
        }
        String application=id();String bindingId=accountBinding==null?bindings.bindFromRoster(user,person,application,key,c).bindingId():accountBinding.getId();
        var row=new QualificationRows.Application();row.setId(application);row.setApplicant(user);row.setPersonId(person);
        row.setBindingId(bindingId);row.setSubmittedAt(c.evaluatedAt());row.setSourceVersion(1);row.setLastEventId(id());
        var result=journal.append(user,op,target,key,command,row.getLastEventId(),"PARTY_APPLICATION",1,null,project(row),
            "Exact verified identifier and profile name match",application,"ROSTER_EXACT_MATCH",application,1L,user,c.evaluatedAt(),201,null);
        one(mapper.insertApplication(row));one(facts.advanceEpoch(Long.parseLong(c.authorityVersion())));
        ManagedPersonService.recheckBeforeCommit(()->readableAccount(user));return result;
    }
    public Page<Application> applications(long user, int page, int size) {
        page(page, size);
        var c = readers.open(user);
        if (!accounts.currentNow(user).accountEligible()) throw conflict();
        return new Page<>(mapper.applications(user,size,(long)(page-1)*size).stream().map(PartyRosterService::project).toList(),page,size,mapper.applicationCount(user),instant(c.evaluatedAt()));
    }

    private QualificationRows.Import owned(long actor, String id, boolean write, LocalDateTime at) {
        access.capabilities(actor, write, at);
        var row = mapper.rosterImport(id);
        if (row == null || row.getCreator() != actor || !access.organization(actor, row.getOrganizationId(), write, at))
            throw problem(HttpStatus.NOT_FOUND, "not-found");
        if (mapper.importCorruption(id) != 0) throw unavailable();
        return row;
    }

    private void finish(long actor, QualificationRows.Import row, boolean write) {
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() {
            @Override
            public void beforeCommit(boolean readOnly) {
                access.requireOrganization(actor, row.getOrganizationId(), write, facts.now());
                access.active(row.getOrganizationId());
                if (!row.getExpiresAt().isAfter(facts.now()))
                    throw "COMMITTED".equals(row.getState()) ? problem(HttpStatus.GONE, "roster-import-expired") : conflict();
            }
        });
    }

    private static void editable(QualificationRows.Import row, LocalDateTime at, boolean commit) {
        if (!row.getExpiresAt().isAfter(at))
            throw problem(commit ? HttpStatus.GONE : HttpStatus.CONFLICT, commit ? "roster-import-expired" : "roster-state-conflict");
        if ("COMMITTED".equals(row.getState())) throw conflict();
    }

    static Import project(QualificationRows.Import row, LocalDateTime at) {
        if(!currentMapping(row)) throw conflict();
        String state = !"COMMITTED".equals(row.getState()) && !row.getExpiresAt().isAfter(at)
                ? "EXPIRED" : row.getState();
        boolean preview = "PREVIEW_READY".equals(state);
        return new Import(row.getId(), row.getOrganizationId(), row.getSourceReference(), state,
                decode(row.getColumnsJson(), new TypeReference<List<Column>>() {
                }), Long.toString(row.getRevision()), instant(row.getExpiresAt()), row.getRowCount(), row.getInvalidRowCount(), row.getImportedCount(),
                row.getMappingJson() == null ? null : decode(row.getMappingJson(), Mapping.class),row.getNamespace(),
                row.getWorkbook()==null || !row.getFileRetainUntil().isAfter(at)?"PURGED":"AVAILABLE",instant(row.getFileRetainUntil()),
                preview ? row.getPlanId() : null, preview ? instant(row.getPlanExpiresAt()) : null);
    }

    private Entry readProjection(long actor,QualificationRows.Entry row,PersonReadContext context) {
        var source=project(row,context.evaluatedAt());
        cn.jualn.miniapp.module.person.bo.PersonBO.Identity identity=null;String bindingState=null;
        if(personAccess.optionalRead(actor,row.getPersonId(),context.evaluatedAt())) {
            readers.identity(row.getPersonId(),context);identity=PersonProjection.identity(row.getPersonId(),facts.head(row.getPersonId()));
            var binding=managed.personBinding(row.getPersonId());
            if(binding==null || personAccess.optionalAccountRead(actor,binding.getUserId(),context.evaluatedAt())) bindingState=binding==null?"UNBOUND":"BOUND";
        }
        return new Entry(source.rosterEntryId(),source.organizationId(),source.realName(),source.studentNumberMasked(),source.majorClass(),source.expectedStageId(),source.baselineStatus(),source.sourceReference(),source.version(),source.personId(),bindingState,identity);
    }
    private Entry project(QualificationRows.Entry row,LocalDateTime at) {
        var v=values(row);
        return new Entry(row.getId(),row.getOrganizationId(),v.realName(),mask(v.studentNumber()),v.majorClass(),row.getExpectedStageId(),row.getBaselineStatus(),
            row.getSourceReference(),Long.toString(row.getRevision()),row.getPersonId(),null,null);
    }
    private QualificationRows.Entry entryRow(String id) {
        var row = mapper.entry(id);
        if (row == null) throw problem(HttpStatus.NOT_FOUND, "not-found");
        return row;
    }

    private PrivateValues values(QualificationRows.Entry row) {
        if (mapper.entryCorruption(row.getId()) != 0) throw unavailable();
        return payload(row);
    }

    private PrivateValues payload(QualificationRows.Entry row) {
        var value = decode(new String(secrets.decrypt("entry", row.getPrivateValues()), StandardCharsets.UTF_8), PrivateValues.class);
        if (!digest(value.studentNumber()).equals(row.getStudentDigest())) throw unavailable();
        return value;
    }

    private byte[] encrypt(String purpose, Object value) {
        return secrets.encrypt(purpose, encode(value).getBytes(StandardCharsets.UTF_8));
    }

    private String digest(String number) {
        return secrets.digest("student-number", number.strip());
    }

    private static boolean currentMapping(QualificationRows.Import row) {
        if(row.getMappingJson()==null) return true;
        try {return tree(row.getMappingJson()).path("columns").has("majorClass");}
        catch(Exception e) {throw unavailable();}
    }
    private List<ParsedRow> parsed(QualificationRows.Import row) {
        if(!currentMapping(row)) throw conflict();
        return decode(new String(secrets.decrypt("preview", row.getPreview()), StandardCharsets.UTF_8), new TypeReference<List<ParsedRow>>() {
        });
    }

    private void validateDuplicates(List<ParsedRow> rows) {
        var counts=new HashMap<String,Integer>();
        for(var row:rows) if(row.values().studentNumber()!=null) counts.merge(row.values().studentNumber().strip(),1,Integer::sum);
        for(var row:rows) if(row.values().studentNumber()!=null && counts.get(row.values().studentNumber().strip())>1
            && row.errors().stream().noneMatch(e->"studentNumber".equals(e.field()) && "DUPLICATE".equals(e.code()))) row.errors().add(new RowError("studentNumber","DUPLICATE"));
    }
    private Correction normalize(Correction c) {
        if (c == null) throw invalid("body");
        reason(c.reason(), c.verificationReference());
        text(c.studentNumber(),100,"studentNumber");
        if (!catalog.confirmed(c.expectedStageId()) || c.baselineStatus() == null || !Set.of("ACTIVE", "SUSPENDED").contains(c.baselineStatus()))
            throw invalid("baselineStatus");
        return new Correction(text(c.realName(), 100, "realName"), c.studentNumber(), c.majorClass()==null?null:text(c.majorClass(), 200, "majorClass"), c.expectedStageId(), c.baselineStatus(), c.reason(), c.verificationReference());
    }

    private void validateQuery(EntryQuery q) {
        if (q.q() != null) text(q.q(), 100, "q");
        if (q.stageId() != null && !catalog.confirmed(q.stageId())) throw invalid("stageId");
        if (q.linkState() != null && !Set.of("UNLINKED", "LINKED", "EXCEPTION").contains(q.linkState())
                || q.baselineStatus() != null && !Set.of("ACTIVE", "SUSPENDED").contains(q.baselineStatus())
                || q.identityVerification() != null && !Set.of("UNKNOWN", "VERIFIED", "DISPUTED").contains(q.identityVerification()))
            throw invalid("filter");
    }

    private static Application project(QualificationRows.Application row) {
        return new Application(row.getId(), Long.toString(row.getApplicant()), "APPROVED", instant(row.getSubmittedAt()), "1", "ROSTER_EXACT_MATCH", instant(row.getSubmittedAt()),row.getPersonId(),row.getBindingId());
    }

    private static String safe(String s, int max) {
        return s == null || s.isBlank() || s.codePointCount(0, s.length()) > max ? null : s;
    }
}
