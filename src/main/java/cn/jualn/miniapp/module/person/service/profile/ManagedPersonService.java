package cn.jualn.miniapp.module.person.service.profile;

import cn.jualn.miniapp.module.person.service.facts.PersonFoundationService;
import cn.jualn.miniapp.module.person.service.facts.PersonFactReaders;
import cn.jualn.miniapp.module.person.service.facts.PersonReadContext;
import cn.jualn.miniapp.module.person.service.binding.PersonBindingTransitions;
import cn.jualn.miniapp.module.person.service.support.ManagedPersonJournal;

import cn.jualn.miniapp.module.person.bo.ManagedPersonBO;
import cn.jualn.miniapp.module.person.bo.ManagedPersonBO.AuditPage;
import cn.jualn.miniapp.module.person.bo.ManagedPersonBO.Create;
import cn.jualn.miniapp.module.person.bo.ManagedPersonBO.Lifecycle;
import cn.jualn.miniapp.module.person.bo.ManagedPersonBO.MajorClassPatch;
import cn.jualn.miniapp.module.person.bo.ManagedPersonBO.NameChange;
import cn.jualn.miniapp.module.person.bo.ManagedPersonBO.Profile;
import cn.jualn.miniapp.module.person.bo.ManagedPersonBO.Provenance;
import cn.jualn.miniapp.module.person.bo.ManagedPersonBO.Registration;

import cn.jualn.miniapp.module.person.bo.PersonBO.Result;
import cn.jualn.miniapp.module.person.entity.ManagedPersonRows;
import cn.jualn.miniapp.module.person.mapper.ManagedPersonMapper;
import cn.jualn.miniapp.module.person.mapper.PersonMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import static cn.jualn.miniapp.module.person.PersonProblems.conflict;
import static cn.jualn.miniapp.module.person.PersonProblems.invalid;
import static cn.jualn.miniapp.module.person.PersonProblems.missing;
import static cn.jualn.miniapp.module.person.PersonProblems.unavailable;

import static cn.jualn.miniapp.module.person.service.facts.PersonFoundationService.one;
import static cn.jualn.miniapp.module.person.service.facts.PersonFoundationService.uuid;

/** Independent Person profile/field owner; uses the same authority gate as dependent writes. */
@Service
@RequiredArgsConstructor
@Transactional(isolation = Isolation.REPEATABLE_READ)
public class ManagedPersonService {

    private final PersonBindingTransitions bindingTransitions;
    private final PersonFactReaders readers;
    private final PersonMapper facts;
    private final ManagedPersonMapper persons;
    private final ManagedPersonAccess access;
    private final ManagedPersonJournal journal;

    public Result<Profile> createManagedPerson(long actor, Create input, String key) {
        if (input == null) throw invalid("body");
        var command = new Create(text(input.realName(), 100, "realName"), nullable(input.majorClass(), 200, "majorClass"),
                text(input.reason(), 2000, "reason"), text(input.verificationReference(), 256, "verificationReference"));
        var c = readers.open(cn.jualn.miniapp.module.person.service.access.PersonAuthorityGate.Access.EXCLUSIVE, actor); var now = c.evaluatedAt();
        access.directory(actor, true, now);
        String op = "createManagedPerson", target = "PERSON_DIRECTORY";
        var prior = journal.replay(actor, op, target, key, command, Profile.class);
        if (prior != null) return prior;
        var row = new ManagedPersonRows.Profile();
        row.setId(uuid()); row.setRealName(command.realName()); row.setStatus("ACTIVE"); row.setRevision(1);
        row.setCreatedAt(now); row.setUpdatedAt(now); row.setLastChangeId(uuid());
        String receipt = uuid();
        journal.event(actor, row.getId(), "PROFILE", row.getId(), 1, row.getLastChangeId(), op,
                command.reason(), command.verificationReference(), null, profileVersion(row), receipt, now);
        one(persons.insertProfile(row));
        if (command.majorClass() != null) {
            setRegistration(actor, row.getId(), command.majorClass(), command.reason(), command.verificationReference(),
                    receipt, now);
        }
        var result = journal.result(profile(row), 201, "/v1/admin/persons/" + row.getId());
        journal.complete(actor, op, target, key, command, receipt, row.getLastChangeId(), result, now);
        one(facts.advanceEpoch(Long.parseLong(c.authorityVersion())));
        access.directory(actor, true, facts.now());recheckBeforeCommit(() -> access.directory(actor,true,facts.now()));
        return result;
    }

    /** Restricted import owner entry. Caller has verified the exact organization/source and holds the common gate. */
    public Profile createFromRoster(long actor, String name, String majorClass, String source, String key, PersonReadContext context) {
        return createScopedProfile(actor, name, majorClass, source, key, context, "commitPartyRosterImport.person", "ROSTER_IMPORT");
    }

    public Profile createFromMentor(long actor, String name, String source, String key, PersonReadContext context) {
        return createScopedProfile(actor, name, null, source, key, context, "createMentorWithPerson.person", "ADMIN_MANUAL");
    }

    private Profile createScopedProfile(long actor, String name, String majorClass, String source, String key,
                                        PersonReadContext context, String operation, String method) {
        context.requireUser(actor);
        var command = new Create(text(name, 100, "realName"), majorClass==null || majorClass.isBlank()?null:nullable(majorClass, 200, "majorClass"), "ROSTER_IMPORT".equals(method)?"Verified roster import":"新增导师人员档案", source);
        var row = new ManagedPersonRows.Profile(); var now = context.evaluatedAt();
        row.setId(uuid()); row.setRealName(command.realName()); row.setStatus("ACTIVE"); row.setRevision(1);
        row.setCreatedAt(now); row.setUpdatedAt(now); row.setLastChangeId(uuid()); String receipt = uuid();
        journal.event(actor, row.getId(), "PROFILE", row.getId(), 1, row.getLastChangeId(), operation,
                command.reason(), "ROSTER_IMPORT".equals(method)?"Verified roster source":source, null, profileVersion(row), receipt, now);
        one(persons.insertProfile(row));
        if (command.majorClass() != null) {
            setRegistration(actor, row.getId(), command.majorClass(), command.reason(), source, receipt, now, method);
        }
        var result = journal.result(profile(row), 201, "/v1/admin/persons/" + row.getId());
        journal.complete(actor, operation, source, key, command, receipt, row.getLastChangeId(), result, now);
        return result.value();
    }

    public Result<Profile> getManagedPerson(long actor, String person) {
        var c = readers.open(actor); access.person(actor, person, false, c.evaluatedAt());
        return journal.result(profile(required(person)), 200, null);
    }

    public Result<Profile> updateManagedPersonName(long actor, String person, NameChange input, String key, String match) {
        if (input == null) throw invalid("body");
        var command = new NameChange(text(input.realName(), 100, "realName"), text(input.reason(), 2000, "reason"),
                text(input.verificationReference(), 256, "verificationReference"));
        var c = readers.open(cn.jualn.miniapp.module.person.service.access.PersonAuthorityGate.Access.EXCLUSIVE, actor); var now = c.evaluatedAt(); access.person(actor, person, true, now);
        String op = "updateManagedPersonName";
        var prior = journal.replay(actor, op, person, key, command, Profile.class); if (prior != null) return prior;
        var row = required(person); journal.match(match, profile(row));
        var result = writeName(actor, person, row, command, key, now);
        one(facts.advanceEpoch(Long.parseLong(c.authorityVersion())));
        access.person(actor, person, true, facts.now());recheckBeforeCommit(() -> access.person(actor,person,true,facts.now()));return result;
    }

    public void correctNameFromPartyPlan(long actor, String person, String name, String reason, String reference,
                                         String key, PersonReadContext context) {
        context.requireUser(actor);
        writeName(actor, person, required(person), new NameChange(text(name, 100, "realName"), reason, reference),
                key, context.evaluatedAt());
    }

    private Result<Profile> writeName(long actor, String person, ManagedPersonRows.Profile row, NameChange command,
                                      String key, LocalDateTime now) {
        String before = profileVersion(row);
        String op = "updateManagedPersonName";
        row.setRealName(command.realName()); row.setRevision(Math.addExact(row.getRevision(), 1));
        row.setUpdatedAt(now); row.setLastChangeId(uuid()); String receipt = uuid();
        journal.event(actor, person, "PROFILE", person, row.getRevision(), row.getLastChangeId(), op,
                command.reason(), command.verificationReference(), before, profileVersion(row), receipt, now);
        one(persons.updateProfile(row));
        var result = journal.result(profile(row), 200, null);
        journal.complete(actor, op, person, key, command, receipt, row.getLastChangeId(), result, now);
        return result;
    }

    public Result<Profile> changeManagedPersonLifecycle(long actor, String person, Lifecycle input, String key, String match) {
        if (input == null || !List.of("ARCHIVE", "RESTORE").contains(input.action())) throw invalid("action");
        var command = new Lifecycle(input.action(), text(input.reason(), 2000, "reason"),
                text(input.verificationReference(), 256, "verificationReference"));
        var c = readers.open(cn.jualn.miniapp.module.person.service.access.PersonAuthorityGate.Access.EXCLUSIVE, actor); var now = c.evaluatedAt(); access.person(actor, person, true, now);
        String op = "changeManagedPersonLifecycle";
        var prior = journal.replay(actor, op, person, key, command, Profile.class); if (prior != null) return prior;
        var row = required(person); journal.match(match, profile(row));
        String next = "ARCHIVE".equals(command.action()) ? "ARCHIVED" : "ACTIVE";
        if (next.equals(row.getStatus())) throw conflict();
        String before = profileVersion(row), receipt = uuid();
        if ("ARCHIVED".equals(next)) {
            var binding = persons.personBinding(person);
            if (binding != null) bindingTransitions.end(actor, binding, command.reason(), command.verificationReference(), receipt, now);
        }
        row.setStatus(next); row.setRevision(Math.addExact(row.getRevision(), 1)); row.setUpdatedAt(now); row.setLastChangeId(uuid());
        journal.event(actor, person, "PROFILE", person, row.getRevision(), row.getLastChangeId(), op,
                command.reason(), command.verificationReference(), before, profileVersion(row), receipt, now);
        one(persons.updateProfile(row));
        var result = journal.result(profile(row), 200, null);
        journal.complete(actor, op, person, key, command, receipt, row.getLastChangeId(), result, now);
        one(facts.advanceEpoch(Long.parseLong(c.authorityVersion())));
        access.person(actor, person, true, facts.now());recheckBeforeCommit(() -> access.person(actor,person,true,facts.now()));return result;
    }

    public AuditPage listManagedPersonHistory(long actor, String person, int page, int size) {
        PersonFoundationService.page(page, size);
        var c = readers.open(actor); access.person(actor, person, false, c.evaluatedAt());
        return new AuditPage(persons.history(person, size, (long) (page - 1) * size), page, size,
                persons.historyCount(person), c.evaluatedAt());
    }

    public Result<Registration> getPersonMajorClassRegistration(long actor, String person) {
        return getPersonMajorClassRegistration(actor, person, null);
    }
    public Result<Registration> getPersonMajorClassRegistration(long actor, String person, String organization) {
        var c = readers.open(actor); access.field(actor, person, organization, false, c.evaluatedAt());
        return journal.result(registration(person, persons.registration(person)), 200, null);
    }

    public Result<Registration> updatePersonMajorClassRegistration(long actor, String person, MajorClassPatch input, String key, String match) {
        return updatePersonMajorClassRegistration(actor, person, null, input, key, match);
    }
    public Result<Registration> updatePersonMajorClassRegistration(long actor, String person, String organization, MajorClassPatch input, String key, String match) {
        if (input == null) throw invalid("body");
        var command = new MajorClassPatch(input.present(), nullable(input.majorClass(), 200, "majorClass"),
                text(input.reason(), 2000, "reason"), text(input.verificationReference(), 256, "verificationReference"));
        var c = readers.open(cn.jualn.miniapp.module.person.service.access.PersonAuthorityGate.Access.EXCLUSIVE, actor); var now = c.evaluatedAt(); access.field(actor, person, organization, true, now);
        String op = "updatePersonMajorClassRegistration";
        var request = java.util.Map.of("patch",command,"organizationId",organization == null ? "" : organization);
        var prior = journal.replay(actor, op, person, key, request, Registration.class); if (prior != null) return prior;
        var before = registration(person, persons.registration(person)); journal.match(match, before);
        String receipt = uuid(), event = null;
        if (command.present()) event = setRegistration(actor, person, command.majorClass(), command.reason(),
                command.verificationReference(), receipt, now);
        var result = journal.result(registration(person, persons.registration(person)), 200, null);
        journal.complete(actor, op, person, key, request, receipt, event, result, now);
        one(facts.advanceEpoch(Long.parseLong(c.authorityVersion())));
        access.field(actor, person, organization, true, facts.now());recheckBeforeCommit(() -> access.field(actor,person,organization,true,facts.now()));return result;
    }

    public void correctClassFromPartyPlan(long actor, String person, String value, String reason, String reference,
                                          String key, PersonReadContext context) {
        context.requireUser(actor);
        var command = new MajorClassPatch(true, nullable(value, 200, "majorClass"), reason, reference);
        var now = context.evaluatedAt();
        var receipt = uuid();
        var event = setRegistration(actor, person, command.majorClass(), reason, reference, receipt, now);
        var result = journal.result(registration(person, persons.registration(person)), 200, null);
        journal.complete(actor, "updatePersonMajorClassRegistration", person, key, command, receipt, event, result, now);
    }

    private String setRegistration(long actor, String person, String value, String reason, String reference, String receipt, LocalDateTime now) {
        return setRegistration(actor,person,value,reason,reference,receipt,now,"ADMIN_REGISTRATION");
    }
    private String setRegistration(long actor,String person,String value,String reason,String reference,String receipt,LocalDateTime now,String sourceKind) {
        var old = persons.registration(person); var row = new ManagedPersonRows.Registration();
        row.setPersonId(person); row.setMajorClass(value); row.setLastAction(value == null ? "CLEAR" : "SET");
        row.setRevision(old == null ? 1 : Math.addExact(old.getRevision(), 1)); row.setSourceKind(sourceKind);
        row.setSourceReference(reference); row.setConfirmedBy(actor); row.setConfirmedAt(now); String event = uuid();
        journal.event(actor, person, "MAJOR_CLASS", person, row.getRevision(), event, "updatePersonMajorClassRegistration",
                reason, "ROSTER_IMPORT".equals(sourceKind)?"Verified roster source":reference, old == null ? null : registrationVersion(old), registrationVersion(row), receipt, now);
        int changed = persons.saveRegistration(row); if (changed != 1 && changed != 2) throw unavailable();
        return event;
    }

    public static void recheckBeforeCommit(Runnable check) {
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
            new org.springframework.transaction.support.TransactionSynchronization() {
                @Override public void beforeCommit(boolean readOnly) {check.run();}
            });
    }
    public ManagedPersonRows.Profile required(String person) { var row = persons.profile(person); if (row == null) throw missing(); return row; }
    public static String text(String value, int max, String field) {
        PersonFoundationService.text(value, max, field, false);
        return value.replaceAll("^[\\s\\p{Z}\\uFEFF]+|[\\s\\p{Z}\\uFEFF]+$", "");
    }
    public static String nullable(String value, int max, String field) { return value == null ? null : text(value, max, field); }
    public static String profileVersion(ManagedPersonRows.Profile p) { return "profile:" + p.getId() + ":" + p.getRevision(); }
    public static String registrationVersion(ManagedPersonRows.Registration r) { return "major-class:" + r.getPersonId() + ":" + r.getRevision(); }
    public static String bindingVersion(ManagedPersonRows.Binding b) { return "binding:" + b.getId() + ":" + b.getRevision(); }
    public static Profile profile(ManagedPersonRows.Profile p) { return new Profile(p.getId(), p.getRealName(), p.getStatus(), profileVersion(p), p.getCreatedAt(), p.getUpdatedAt(), p.getLastChangeId()); }
    public static Registration registration(String person, ManagedPersonRows.Registration r) {
        if (r == null) return new Registration(person, null, null, null, "major-class:" + person + ":0");
        // Source references require their own source permission. Omit them from this shared projection.
        return new Registration(person, r.getMajorClass(), r.getLastAction(),
                new Provenance(r.getSourceKind(), null, Long.toString(r.getConfirmedBy()), r.getConfirmedAt()), registrationVersion(r));
    }
}
