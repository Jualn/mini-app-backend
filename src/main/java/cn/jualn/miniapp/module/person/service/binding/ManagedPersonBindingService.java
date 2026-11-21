package cn.jualn.miniapp.module.person.service.binding;

import cn.jualn.miniapp.module.person.bo.MentorWorkflowBO;
import cn.jualn.miniapp.module.person.service.qualification.MentorOperationAccess;
import cn.jualn.miniapp.module.person.mapper.QualificationMapper;
import cn.jualn.miniapp.module.person.mapper.MentorWorkflowMapper;
import cn.jualn.miniapp.module.person.service.facts.PersonFoundationService;
import cn.jualn.miniapp.module.person.service.facts.PersonFactReaders;
import cn.jualn.miniapp.module.person.service.facts.PersonReadContext;
import cn.jualn.miniapp.module.person.service.profile.ManagedPersonService;
import cn.jualn.miniapp.module.person.service.profile.ManagedPersonAccess;
import cn.jualn.miniapp.module.person.service.support.ManagedPersonJournal;

import cn.jualn.miniapp.module.person.bo.ManagedPersonBO.AccountSlot;
import cn.jualn.miniapp.module.person.bo.ManagedPersonBO.Bind;
import cn.jualn.miniapp.module.person.bo.ManagedPersonBO.Binding;
import cn.jualn.miniapp.module.person.bo.ManagedPersonBO.BindingChange;
import cn.jualn.miniapp.module.person.bo.ManagedPersonBO.BindingVersion;
import cn.jualn.miniapp.module.person.bo.ManagedPersonBO.End;
import cn.jualn.miniapp.module.person.bo.ManagedPersonBO.MyBinding;
import cn.jualn.miniapp.module.person.bo.ManagedPersonBO.PersonSlot;
import cn.jualn.miniapp.module.person.bo.ManagedPersonBO.Profile;
import cn.jualn.miniapp.module.person.bo.ManagedPersonBO.Reassociate;

import cn.jualn.miniapp.module.person.bo.PersonBO.Result;
import cn.jualn.miniapp.module.person.entity.ManagedPersonRows;
import cn.jualn.miniapp.module.person.mapper.ManagedPersonMapper;
import cn.jualn.miniapp.module.person.mapper.PersonMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import static cn.jualn.miniapp.module.person.PersonProblems.invalid;
import static cn.jualn.miniapp.module.person.PersonProblems.missing;
import static cn.jualn.miniapp.module.person.PersonProblems.problem;
import static cn.jualn.miniapp.module.person.PersonProblems.unavailable;

import static cn.jualn.miniapp.module.person.service.profile.ManagedPersonService.text;
import static cn.jualn.miniapp.module.person.service.facts.PersonFoundationService.one;
import static cn.jualn.miniapp.module.person.service.facts.PersonFoundationService.uuid;
import static cn.jualn.miniapp.module.person.service.support.PersonnelValues.key;

@Service
@RequiredArgsConstructor
@Transactional(isolation = Isolation.REPEATABLE_READ)
public class ManagedPersonBindingService {
    @org.springframework.beans.factory.annotation.Autowired
    private cn.jualn.miniapp.module.admin.operation.service.AdminAuditJournal managementAudit;

    private final PersonBindingTransitions transitions;
    private final PersonFactReaders readers;
    private final PersonMapper facts;
    private final ManagedPersonMapper persons;
    private final ManagedPersonAccess access;
    private final ManagedPersonJournal journal;
    private final ManagedPersonService profiles;
    private final MentorOperationAccess mentorAccess;
    private final QualificationMapper mentorRecords;
    private final MentorWorkflowMapper mentorCandidates;

    public Result<Binding> createUserPersonBinding(long actor, Bind input, String key) {
        var command = normalize(input); long user = PersonFoundationService.userId(command.userId());
        var c = readers.open(cn.jualn.miniapp.module.person.service.access.PersonAuthorityGate.Access.EXCLUSIVE, actor, user); var now = c.evaluatedAt();
        authorize(actor, command.personId(), user, now);
        String op = "createUserPersonBinding";
        var prior = journal.replay(actor, op, command.personId(), key, command, Binding.class);
        if (prior != null) return prior;
        versions(command); access.usable(user, now);ManagedPersonService.recheckBeforeCommit(() -> access.usable(user,facts.now()));
        if (persons.personBinding(command.personId()) != null || persons.accountBinding(user) != null) throw bindingConflict();
        String receipt = uuid(); var row = start(actor, command, receipt, now);
        var result = journal.result(binding(row), 201, "/v1/admin/user-person-bindings/" + row.getId());
        // Start writes exactly one event whose identity is the binding ID at revision one.
        journal.complete(actor, op, command.personId(), key, command, receipt, row.getId(), result, now);
        one(facts.advanceEpoch(Long.parseLong(c.authorityVersion())));
        finalAccess(actor, command.personId(), user); return result;
    }

    public Result<BindingChange> reassociateUserPersonBinding(long actor, Reassociate input, String key) {
        if (input == null || input.endedBindings() == null || input.endedBindings().isEmpty() || input.endedBindings().size() > 2
                || input.endedBindings().stream().map(BindingVersion::bindingId).distinct().count() != input.endedBindings().size()) throw invalid("endedBindings");
        var command = normalize(new Bind(input.personId(), input.userId(), input.personVersion(), input.personBindingVersion(),
                input.userBindingVersion(), input.reason(), input.verificationReference()));
        var requested = input.endedBindings().stream().sorted(java.util.Comparator.comparing(BindingVersion::bindingId)).toList();
        var normalized = new Reassociate(requested, command.personId(), command.userId(), command.personVersion(), command.personBindingVersion(), command.userBindingVersion(), command.reason(), command.verificationReference());
        long user = PersonFoundationService.userId(command.userId());
        var c = readers.open(cn.jualn.miniapp.module.person.service.access.PersonAuthorityGate.Access.EXCLUSIVE, actor, user); var now = c.evaluatedAt(); authorize(actor, command.personId(), user, now);
        var rows = new LinkedHashMap<String, ManagedPersonRows.Binding>();
        for (var expected : requested) {
            var row = persons.binding(expected.bindingId()); if (row == null) throw missing();
            authorize(actor, row.getPersonId(), row.getUserId(), now); rows.put(row.getId(), row);
        }
        String op = "reassociateUserPersonBinding";
        var prior = journal.replay(actor, op, command.personId(), key, normalized, BindingChange.class);
        if (prior != null) return prior;
        versions(command); access.usable(user, now);ManagedPersonService.recheckBeforeCommit(() -> access.usable(user,facts.now()));
        var active = new LinkedHashMap<String, ManagedPersonRows.Binding>();
        for (var row : new ManagedPersonRows.Binding[]{persons.personBinding(command.personId()), persons.accountBinding(user)}) {
            if (row != null) active.put(row.getId(), row);
        }
        if (!active.keySet().equals(rows.keySet())) throw bindingConflict();
        for (var expected : requested) {
            var row = rows.get(expected.bindingId());
            if (!"ACTIVE".equals(row.getStatus()) || !ManagedPersonService.bindingVersion(row).equals(expected.version())) throw problem(HttpStatus.PRECONDITION_FAILED, "precondition-failed");
        }
        String receipt = uuid();
        for (var row : rows.values()) transitions.end(actor, row, command.reason(), command.verificationReference(), receipt, now);
        var created = start(actor, command, receipt, now);
        var result = journal.result(new BindingChange(new ArrayList<>(rows.keySet()), binding(created)), 201, "/v1/admin/user-person-bindings/" + created.getId());
        journal.complete(actor, op, command.personId(), key, normalized, receipt, created.getId(), result, now);
        one(facts.advanceEpoch(Long.parseLong(c.authorityVersion())));
        finalAccess(actor, command.personId(), user);
        for (var row : rows.values()) finalAccess(actor, row.getPersonId(), row.getUserId());
        return result;
    }

    private record EndCommand(String bindingId, End command) {}
    public Result<Binding> endUserPersonBinding(long actor, String id, End input, String key, String match) {
        if (input == null) throw invalid("body");
        var command = new EndCommand(id, new End(text(input.reason(), 2000, "reason"), text(input.verificationReference(), 256, "verificationReference")));
        var c = readers.open(cn.jualn.miniapp.module.person.service.access.PersonAuthorityGate.Access.EXCLUSIVE, actor); var now = c.evaluatedAt(); var row = persons.binding(id); if (row == null) throw missing();
        authorize(actor, row.getPersonId(), row.getUserId(), now);
        String op = "endUserPersonBinding";
        var prior = journal.replay(actor, op, row.getPersonId(), key, command, Binding.class); if (prior != null) return prior;
        journal.match(match, binding(row)); if (!"ACTIVE".equals(row.getStatus())) throw bindingConflict();
        String receipt = uuid(); String event = transitions.end(actor, row, command.command().reason(), command.command().verificationReference(), receipt, now);
        var result = journal.result(binding(row), 200, null);
        journal.complete(actor, op, row.getPersonId(), key, command, receipt, event, result, now);
        one(facts.advanceEpoch(Long.parseLong(c.authorityVersion())));
        finalAccess(actor, row.getPersonId(), row.getUserId()); return result;
    }

    public Result<Binding> getUserPersonBinding(long actor, String id) {
        var c = readers.open(actor); var row = persons.binding(id); if (row == null) throw missing();
        access.person(actor, row.getPersonId(), false, c.evaluatedAt()); access.account(actor, row.getUserId(), c.evaluatedAt());
        return journal.result(binding(row), 200, null);
    }
    public Result<PersonSlot> getPersonAccountBinding(long actor, String person) {
        var c = readers.open(actor); access.person(actor, person, false, c.evaluatedAt());
        var row = persons.personBinding(person);
        if (row != null) access.account(actor, row.getUserId(), c.evaluatedAt());
        return journal.result(new PersonSlot(row == null ? "UNBOUND" : "BOUND", person, row == null ? null : binding(row), personSlot(person)), 200, null);
    }
    public Result<AccountSlot> getAccountPersonBinding(long actor, long user) {
        var c = readers.open(actor, user); access.capability(actor, true, c.evaluatedAt()); access.account(actor, user, c.evaluatedAt());
        var row = persons.accountBinding(user);
        if (row == null) access.directory(actor, true, c.evaluatedAt()); else access.person(actor, row.getPersonId(), false, c.evaluatedAt());
        return journal.result(new AccountSlot(row == null ? "UNBOUND" : "BOUND", Long.toString(user), row == null ? null : row.getPersonId(), row == null ? null : row.getId(), accountSlot(user)), 200, null);
    }
    public Result<MyBinding> getMyPersonBinding(long user) {
        var c = readers.open(user); access.usable(user, c.evaluatedAt()); var row = persons.accountBinding(user);
        if (row == null) return journal.result(new MyBinding("UNBOUND", Long.toString(user), null, null, null, accountSlot(user), c.evaluatedAt()), 200, null);
        var profile = profiles.required(row.getPersonId());
        if (!"ACTIVE".equals(profile.getStatus())) throw unavailable();
        return journal.result(new MyBinding("BOUND", Long.toString(user), row.getPersonId(), row.getId(), profile.getStatus(), accountSlot(user), c.evaluatedAt()), 200, null);
    }

    public record BindingPage(List<Binding> items, int page, int pageSize, long totalItems, LocalDateTime evaluatedAt) {}
    public BindingPage listPersonAccountBindings(long actor, String person, int page, int size) {
        PersonFoundationService.page(page, size); var c = readers.open(actor); access.person(actor, person, false, c.evaluatedAt());
        var items = persons.bindingHistory(person, size, (long) (page - 1) * size);
        for (var row : items) access.account(actor, row.getUserId(), c.evaluatedAt());
        return new BindingPage(items.stream().map(ManagedPersonBindingService::binding).toList(), page, size, persons.bindingHistoryCount(person), c.evaluatedAt());
    }

    public java.util.Map<String, Object> getMyPersonCommandReceipt(long actor, String op, String target, String key) {
        ManagedPersonJournal.commandKey(key);
        if (!List.of("createManagedPerson", "createUserPersonBinding", "reassociateUserPersonBinding").contains(op)) throw invalid("operationId");
        var c = readers.open(actor); var now = c.evaluatedAt();
        if ("createManagedPerson".equals(op)) {
            if (!"PERSON_DIRECTORY".equals(target)) throw invalid("target"); access.directory(actor, true, now);
        } else access.person(actor, target, false, now);
        var receipt = facts.receipt(actor, op, target, key);
        if (receipt == null) return java.util.Map.of("state", "NOT_FOUND");
        if (receipt.getStatus() == 202) return java.util.Map.of("state", "IN_PROGRESS");
        var event = facts.event(receipt.getEventId());
        if (event == null || !receipt.getId().equals(event.getReceiptId())) throw unavailable();
        Object result;
        if ("createManagedPerson".equals(op)) result = journal.decode(receipt.getResultJson(), Profile.class);
        else if ("createUserPersonBinding".equals(op)) {
            var binding = journal.decode(receipt.getResultJson(), Binding.class);
            access.person(actor, binding.personId(), false, now); access.account(actor, PersonFoundationService.userId(binding.userId()), now); result = binding;
        } else {
            var change = journal.decode(receipt.getResultJson(), BindingChange.class);
            access.person(actor, change.binding().personId(), false, now); access.account(actor, PersonFoundationService.userId(change.binding().userId()), now);
            for (var id : change.endedBindingIds()) {
                var row = persons.binding(id); if (row == null) throw unavailable();
                access.person(actor, row.getPersonId(), false, now); access.account(actor, row.getUserId(), now);
            }
            result = change;
        }
        return java.util.Map.of("state", "SUCCEEDED", "operationId", op, "httpStatus", receipt.getStatus(), "result", result,
                "etag", receipt.getEtag(), "location", receipt.getLocation());
    }

    public Result<MentorWorkflowBO.BindingView> getMentorBinding(long actor, String person) {
        var c=readers.open(actor); mentorBindingAccess(actor,person,c.evaluatedAt());
        var value=mentorBinding(person,c.evaluatedAt());
        return new Result<>(value,"\""+value.version()+"\"",null,200);
    }

    private void mentorBindingAccess(long actor,String person,LocalDateTime at) {
        mentorAccess.writable(actor,"mentor:binding-manage",person,at);
        if(mentorRecords.mentor(person)==null) throw missing();
    }

    private MentorWorkflowBO.BindingView mentorBinding(String person,LocalDateTime at) {
        var p=profiles.required(person); var row=persons.personBinding(person);
        String version="mentor-binding:"+p.getRevision()+":"+p.getBindingRevision();
        return new MentorWorkflowBO.BindingView(person,row==null?null:Long.toString(row.getUserId()),
                row==null?null:mentorCandidates.nickname(row.getUserId()),row==null?null:row.getId(),version,at.toInstant(java.time.ZoneOffset.UTC));
    }

    private record MentorBindingReceipt(MentorWorkflowBO.BindingView view, Binding binding) {}

    public Result<MentorWorkflowBO.BindingView> changeMentorBinding(long actor,String person,
            MentorWorkflowBO.BindingChange input,String key,String match) {
        key(key);
        if(input==null || input.action()==null || !java.util.Set.of("ASSOCIATE","END").contains(input.action())
                || "ASSOCIATE".equals(input.action()) && input.userId()==null || "END".equals(input.action()) && input.userId()!=null) throw invalid("body");
        var command=new MentorWorkflowBO.BindingChange(input.action(),input.userId(),
                text(input.reason(),2000,"reason"),text(input.verificationReference(),256,"verificationReference"));
        long user=input.userId()==null?actor:PersonFoundationService.userId(input.userId());
        var c=readers.open(cn.jualn.miniapp.module.person.service.access.PersonAuthorityGate.Access.EXCLUSIVE,actor,user);
        mentorBindingAccess(actor,person,c.evaluatedAt());
        var prior=journal.replay(actor,"changeMentorAccountBinding",person,key,command,MentorBindingReceipt.class);
        if(prior!=null) return new Result<>(prior.value().view(),prior.etag(),null,200);
        var before=mentorBinding(person,c.evaluatedAt());
        if(match==null) throw problem(HttpStatus.PRECONDITION_REQUIRED,"precondition-required");
        if(!match.matches("\"[^\"\\r\\n]+\"")) throw invalid("If-Match");
        if(!("\""+before.version()+"\"").equals(match)) throw problem(HttpStatus.PRECONDITION_FAILED,"precondition-failed");
        if(!"ACTIVE".equals(profiles.required(person).getStatus())) throw problem(HttpStatus.CONFLICT,"person-archived");
        var current=persons.personBinding(person);
        if(current!=null && current.getUserId()==actor) throw problem(HttpStatus.FORBIDDEN,"person-self-governance-forbidden");
        if("ASSOCIATE".equals(command.action())) {
            if(user==actor) throw problem(HttpStatus.FORBIDDEN,"person-self-governance-forbidden");
            access.usable(user,c.evaluatedAt());
            var occupied=persons.accountBinding(user);
            if(occupied!=null && !person.equals(occupied.getPersonId())) throw bindingConflict();
        } else if(current==null) throw bindingConflict();
        String receipt=uuid(),event; ManagedPersonRows.Binding started=null;
        String endedEvent=current==null?null:transitions.end(actor,current,command.reason(),command.verificationReference(),receipt,c.evaluatedAt());
        if("ASSOCIATE".equals(command.action())) {
            var bind=new Bind(person,Long.toString(user),ManagedPersonService.profileVersion(profiles.required(person)),personSlot(person),accountSlot(user),command.reason(),command.verificationReference());
            started=start(actor,bind,receipt,c.evaluatedAt()); event=started.getId();
        } else event=endedEvent;
        var value=mentorBinding(person,c.evaluatedAt());
        var result=new Result<>(value,"\""+value.version()+"\"",null,200);
        var saved=new MentorBindingReceipt(value,binding(started==null?current:started));
        journal.complete(actor,"changeMentorAccountBinding",person,key,command,receipt,event,
                new Result<>(saved,result.etag(),null,started==null?200:201),c.evaluatedAt());
        one(facts.advanceEpoch(Long.parseLong(c.authorityVersion())));
        ManagedPersonService.recheckBeforeCommit(() -> {
            mentorBindingAccess(actor,person,facts.now());
            if("ASSOCIATE".equals(command.action())) access.usable(user,facts.now());
        });
        return result;
    }

    private void versions(Bind command) {
        var profile = profiles.required(command.personId());
        if (!ManagedPersonService.profileVersion(profile).equals(command.personVersion()) || !personSlot(command.personId()).equals(command.personBindingVersion())
                || !accountSlot(PersonFoundationService.userId(command.userId())).equals(command.userBindingVersion())) throw problem(HttpStatus.PRECONDITION_FAILED, "precondition-failed");
        if (!"ACTIVE".equals(profile.getStatus())) throw problem(HttpStatus.CONFLICT, "person-archived");
    }
    private void authorize(long actor, String person, long user, LocalDateTime at) {
        access.person(actor, person, true, at); access.account(actor, user, at);
        if (actor == user) throw problem(HttpStatus.FORBIDDEN, "person-self-governance-forbidden");
    }
    private void finalAccess(long actor, String person, long user) {
        var now = facts.now(); authorize(actor, person, user, now);
        ManagedPersonService.recheckBeforeCommit(() -> authorize(actor,person,user,facts.now()));
    }
    private ManagedPersonRows.Binding start(long actor, Bind command, String receipt, LocalDateTime now) {
        var row = new ManagedPersonRows.Binding(); row.setId(uuid()); row.setPersonId(command.personId());
        row.setUserId(PersonFoundationService.userId(command.userId())); row.setStatus("ACTIVE"); row.setRevision(1);
        row.setStartedAt(now); row.setVerificationMethod("ADMIN_MANUAL"); row.setSourceReference(command.verificationReference()); row.setStartedBy(actor);
        try { one(persons.insertBinding(row)); } catch (DuplicateKeyException e) { throw bindingConflict(); }
        one(persons.advancePersonSlot(row.getPersonId())); int n = persons.advanceAccountSlot(row.getUserId()); if (n != 1 && n != 2) throw unavailable();
        journal.event(actor, row.getPersonId(), "BINDING", row.getId(), 1, row.getId(), "createUserPersonBinding",
                command.reason(), command.verificationReference(), null, ManagedPersonService.bindingVersion(row), receipt, now);
        managementAudit.duty(row.getId(),receipt,actor,"BINDING","GRANT",Long.toString(row.getUserId()),row.getPersonId(),"PERSON_BINDING",row.getId(),null,
            managementAudit.tree(java.util.Map.of("status","ACTIVE","bindingId",row.getId())),now);
        return row;
    }
    public Binding bindFromRoster(long user, String person, String source, String key, PersonReadContext context) {
        context.requireUser(user);var now=context.evaluatedAt();access.usable(user,now);
        var profile=profiles.required(person);
        if(!"ACTIVE".equals(profile.getStatus()) || persons.personBinding(person)!=null || persons.accountBinding(user)!=null) throw bindingConflict();
        var row=new ManagedPersonRows.Binding();row.setId(uuid());row.setPersonId(person);row.setUserId(user);
        row.setStatus("ACTIVE");row.setRevision(1);row.setStartedAt(now);row.setStartedBy(user);
        row.setVerificationMethod("ROSTER_EXACT_MATCH");row.setSourceReference(source);
        try {one(persons.insertBinding(row));} catch(DuplicateKeyException e) {throw bindingConflict();}
        one(persons.advancePersonSlot(person));int changed=persons.advanceAccountSlot(user);if(changed!=1 && changed!=2) throw unavailable();
        String receipt=uuid();
        journal.event(user,person,"BINDING",row.getId(),1,row.getId(),"createMyPartyIdentityApplication.binding",
            "Exact verified identifier and profile name match",source,null,ManagedPersonService.bindingVersion(row),receipt,now);
        var result=journal.result(binding(row),201,null);
        journal.complete(user,"createMyPartyIdentityApplication.binding",source,key,java.util.List.of(person,source),receipt,row.getId(),result,now);
        ManagedPersonService.recheckBeforeCommit(()->access.usable(user,facts.now()));
        return binding(row);
    }
    private String personSlot(String person) { return "person-binding:" + person + ":" + profiles.required(person).getBindingRevision(); }
    private String accountSlot(long user) { return "account-binding:" + user + ":" + persons.accountSlot(user); }
    private static Bind normalize(Bind c) {
        if (c == null) throw invalid("body");
        return new Bind(text(c.personId(), 128, "personId"), Long.toString(PersonFoundationService.userId(c.userId())),
                text(c.personVersion(), 128, "personVersion"), text(c.personBindingVersion(), 128, "personBindingVersion"), text(c.userBindingVersion(), 128, "userBindingVersion"),
                text(c.reason(), 2000, "reason"), text(c.verificationReference(), 256, "verificationReference"));
    }
    static Binding binding(ManagedPersonRows.Binding r) { return new Binding(r.getId(), Long.toString(r.getUserId()), r.getPersonId(), r.getStatus(), ManagedPersonService.bindingVersion(r), r.getStartedAt(), r.getVerificationMethod(), r.getSourceReference(), Long.toString(r.getStartedBy()), r.getEndedAt(), r.getEndedBy() == null ? null : Long.toString(r.getEndedBy()), r.getEndReason()); }
    private static RuntimeException bindingConflict() { return problem(HttpStatus.CONFLICT, "person-binding-conflict"); }
}
