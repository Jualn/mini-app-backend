package cn.jualn.miniapp.module.person.service.profile;

import cn.jualn.miniapp.module.person.service.facts.PersonFoundationService;
import cn.jualn.miniapp.module.person.service.facts.PersonFactReaders;
import cn.jualn.miniapp.module.person.service.facts.PersonReadContext;
import cn.jualn.miniapp.module.person.service.support.ManagedPersonJournal;
import cn.jualn.miniapp.module.person.service.support.PersonPrivateData;
import cn.jualn.miniapp.module.person.bo.ManagedPersonBO.Identifier;
import cn.jualn.miniapp.module.person.bo.ManagedPersonBO.IdentifierChange;
import cn.jualn.miniapp.module.person.bo.ManagedPersonBO.IdentifierCreate;
import cn.jualn.miniapp.module.person.bo.ManagedPersonBO.IdentifierValue;
import cn.jualn.miniapp.module.person.bo.ManagedPersonBO.Namespace;
import cn.jualn.miniapp.module.person.bo.PersonBO.Result;
import cn.jualn.miniapp.module.person.entity.ManagedPersonRows;
import cn.jualn.miniapp.module.person.mapper.ManagedPersonMapper;
import cn.jualn.miniapp.module.person.mapper.PersonMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import static cn.jualn.miniapp.module.person.PersonProblems.conflict;
import static cn.jualn.miniapp.module.person.PersonProblems.invalid;
import static cn.jualn.miniapp.module.person.PersonProblems.missing;
import static cn.jualn.miniapp.module.person.PersonProblems.problem;
import static cn.jualn.miniapp.module.person.PersonProblems.unavailable;
import static cn.jualn.miniapp.module.person.service.profile.ManagedPersonService.text;
import static cn.jualn.miniapp.module.person.service.facts.PersonFoundationService.one;
import static cn.jualn.miniapp.module.person.service.facts.PersonFoundationService.uuid;

@Service
@RequiredArgsConstructor
@Transactional(isolation = Isolation.REPEATABLE_READ)
public class ManagedPersonIdentifierService {
    private final PersonFactReaders readers;
    private final PersonMapper facts;
    private final ManagedPersonMapper persons;
    private final ManagedPersonAccess access;
    private final ManagedPersonJournal journal;
    private final PersonPrivateData secrets;
    private final PersonIdentifierNamespaces namespaces;

    public List<Namespace> listPersonIdentifierNamespaces(long actor) {
        var c = readers.open(actor);
        access.capability(actor, false, c.evaluatedAt());
        return namespaces.items();
    }

    public record IdentifierList(List<Identifier> items) {
    }

    public Result<IdentifierList> listPersonIdentifiers(long actor, String person) {
        var c = readers.open(actor);
        access.person(actor, person, false, c.evaluatedAt());
        var rows = persons.identifiers(person);
        if (rows.size() > 100) throw unavailable();
        return journal.result(new IdentifierList(rows.stream().map(ManagedPersonIdentifierService::identifier).toList()), 200, null);
    }

    public Result<Identifier> createPersonIdentifier(long actor, String person, IdentifierCreate input, String key) {
        if (input == null || input.verification() == null || !List.of("UNKNOWN", "VERIFIED", "DISPUTED").contains(input.verification()))
            throw invalid("verification");
        var command = new IdentifierCreate(input.identifierType(), input.namespace(), text(input.value(), 100, "value"), input.verification(),
                text(input.reason(), 2000, "reason"), text(input.verificationReference(), 256, "verificationReference"), input.personVersion());
        var c = readers.open(cn.jualn.miniapp.module.person.service.access.PersonAuthorityGate.Access.EXCLUSIVE, actor);
        var now = c.evaluatedAt();
        access.person(actor, person, true, now);
        String op = "createPersonIdentifier";
        var prior = journal.replay(actor, op, person, key, command, Identifier.class);
        if (prior != null) return prior;
        namespaces.require(command.identifierType(), command.namespace());
        var profile = persons.profile(person);
        if (!ManagedPersonService.profileVersion(profile).equals(command.personVersion()))
            throw problem(HttpStatus.PRECONDITION_FAILED, "precondition-failed");
        if (!"ACTIVE".equals(profile.getStatus())) throw problem(HttpStatus.CONFLICT, "person-archived");
        if (persons.identifiers(person).size() >= 100) throw conflict();
        var result = writeIdentifier(actor, person, command, key, now);
        one(facts.advanceEpoch(Long.parseLong(c.authorityVersion())));
        access.person(actor, person, true, facts.now());
        ManagedPersonService.recheckBeforeCommit(() -> access.person(actor, person, true, facts.now()));
        return result;
    }

    private Result<Identifier> writeIdentifier(long actor, String person, IdentifierCreate command,
                                               String key, LocalDateTime now) {
        String op = "createPersonIdentifier";
        var row = new ManagedPersonRows.Identifier();
        row.setId(uuid());
        row.setPersonId(person);
        row.setIdentifierType(command.identifierType());
        row.setNamespace(command.namespace());
        row.setStatus("ACTIVE");
        row.setVerification(command.verification());
        row.setRevision(1);
        row.setSourceReference(command.verificationReference());
        protect(row, command.value());
        String receipt = uuid(), event = uuid();
        try {
            one(persons.insertIdentifier(row));
        } catch (DuplicateKeyException e) {
            throw identifierConflict();
        }
        one(persons.saveIdentifierVersion(row));
        var result = journal.result(identifier(row), 201, "/v1/admin/persons/" + person + "/identifiers/" + row.getId());
        journal.event(actor, person, "IDENTIFIER", row.getId(), 1, event, op, command.reason(), command.verificationReference(), null, version(row), receipt, now);
        journal.complete(actor, op, person, key, command, receipt, event, result, now);
        return result;
    }

    public Result<Identifier> changePersonIdentifier(long actor, String person, String id, IdentifierChange input, String key, String match) {
        if (input == null || input.action() == null || !List.of("CORRECT", "REVOKE", "VERIFY", "DISPUTE").contains(input.action())
                || ("CORRECT".equals(input.action()) != (input.value() != null))) throw invalid("action");
        var command = new IdentifierChange(input.action(), input.value() == null ? null : text(input.value(), 100, "value"),
                text(input.reason(), 2000, "reason"), text(input.verificationReference(), 256, "verificationReference"));
        var c = readers.open(cn.jualn.miniapp.module.person.service.access.PersonAuthorityGate.Access.EXCLUSIVE, actor);
        var now = c.evaluatedAt();
        access.person(actor, person, true, now);
        String op = "changePersonIdentifier", target = person + ":" + id;
        var prior = journal.replay(actor, op, target, key, command, Identifier.class);
        if (prior != null) return prior;
        var row = persons.identifier(person, id);
        if (row == null) throw missing();
        journal.match(match, identifier(row));
        String before = version(row);
        row.setRevision(Math.addExact(row.getRevision(), 1));
        row.setSourceReference(command.verificationReference());
        switch (command.action()) {
            case "CORRECT" -> protect(row, command.value());
            case "REVOKE" -> {
                if (!"ACTIVE".equals(row.getStatus())) throw conflict();
                row.setStatus("REVOKED");
            }
            case "VERIFY" -> row.setVerification("VERIFIED");
            case "DISPUTE" -> row.setVerification("DISPUTED");
            default -> throw invalid("action");
        }
        one(persons.updateIdentifier(row));
        one(persons.saveIdentifierVersion(row));
        String receipt = uuid(), event = uuid();
        var result = journal.result(identifier(row), 200, null);
        journal.event(actor, person, "IDENTIFIER", id, row.getRevision(), event, op, command.reason(), command.verificationReference(), before, version(row), receipt, now);
        journal.complete(actor, op, target, key, command, receipt, event, result, now);
        one(facts.advanceEpoch(Long.parseLong(c.authorityVersion())));
        access.person(actor, person, true, facts.now());
        ManagedPersonService.recheckBeforeCommit(() -> access.person(actor, person, true, facts.now()));
        return result;
    }

    public Result<IdentifierValue> getPersonIdentifierValue(long actor, String person, String id, String version) {
        var c = readers.open(actor);
        access.person(actor, person, true, c.evaluatedAt());
        var row = persons.identifier(person, id);
        if (row == null) throw missing();
        long revision = row.getRevision();
        if (version != null) {
            String prefix = "identifier:" + id + ":";
            if (!version.startsWith(prefix)) throw missing();
            try {
                revision = Long.parseLong(version.substring(prefix.length()));
            } catch (NumberFormatException e) {
                throw missing();
            }
            if (revision < 1 || revision > row.getRevision() || !(prefix + revision).equals(version)) throw missing();
        }
        var envelope = persons.identifierValue(id, revision);
        if (envelope == null) throw missing();
        var value = new String(secrets.decrypt(purpose(id), envelope.getPrivateValue()), StandardCharsets.UTF_8);
        var projection = new IdentifierValue(id, person, value, "identifier:" + id + ":" + revision);
        // The current dedicated GET exposes the same resource ETag as a mutation; history never does.
        var etag = revision == row.getRevision() ? journal.result(identifier(row), 200, null).etag()
                : journal.result(projection, 200, null).etag();
        return new Result<>(projection, etag, null, 200);
    }

    private void protect(ManagedPersonRows.Identifier row, String value) {
        var digest = secrets.digest("person-identifier", journal.encode(List.of(row.getIdentifierType(), row.getNamespace(), value)));
        String owner = persons.occupation(row.getIdentifierType(), row.getNamespace(), digest);
        if (owner == null) {
            try {
                one(persons.occupy(row.getIdentifierType(), row.getNamespace(), digest, row.getPersonId()));
            } catch (DuplicateKeyException e) {
                throw identifierConflict();
            }
        } else if (!owner.equals(row.getPersonId())) throw identifierConflict();
        row.setValueMasked("***");
        row.setPrivateValue(secrets.encrypt(purpose(row.getId()), value.getBytes(StandardCharsets.UTF_8)));
    }

    static String purpose(String id) {
        return "person-identifier:" + id;
    }

    public String rosterNamespace() {
        return namespaces.items().get(0).namespace();
    }

    public String rosterValue(ManagedPersonRows.Identifier row) {
        return new String(secrets.decrypt(purpose(row.getId()), row.getPrivateValue()), StandardCharsets.UTF_8);
    }

    public String rosterPerson(String raw) {
        String value = text(raw, 100, "studentNumber");
        var namespace = namespaces.items().get(0);
        String digest = secrets.digest("person-identifier", journal.encode(List.of(namespace.identifierType(), namespace.namespace(), value)));
        var owner = persons.occupation(namespace.identifierType(), namespace.namespace(), digest);
        if (owner == null) return null;
        var profile = persons.profile(owner);
        if (profile == null) throw unavailable();
        if (!"ACTIVE".equals(profile.getStatus())) throw identifierConflict();
        boolean current = persons.identifiers(owner).stream().anyMatch(i -> "ACTIVE".equals(i.getStatus()) && "VERIFIED".equals(i.getVerification())
                && namespace.namespace().equals(i.getNamespace()) && value.equals(new String(secrets.decrypt(purpose(i.getId()), i.getPrivateValue()), StandardCharsets.UTF_8)));
        if (!current) throw identifierConflict();
        return owner;
    }

    public void supplementFromPartyPlan(long actor, String person, String value, String reason, String reference,
                                        String key, PersonReadContext context) {
        context.requireUser(actor);
        if (persons.identifiers(person).size() >= 100 || rosterPerson(value) != null) throw identifierConflict();
        var namespace = namespaces.items().get(0);
        var command = new IdentifierCreate(namespace.identifierType(), namespace.namespace(), value, "VERIFIED",
                reason, reference, ManagedPersonService.profileVersion(persons.profile(person)));
        writeIdentifier(actor, person, command, key, context.evaluatedAt());
    }

    public void registerFromRoster(long actor, String person, String raw, String source, String key, PersonReadContext context) {
        context.requireUser(actor);
        var namespace = namespaces.items().get(0);
        var now = context.evaluatedAt();
        var row = new ManagedPersonRows.Identifier();
        row.setId(uuid());
        row.setPersonId(person);
        row.setIdentifierType(namespace.identifierType());
        row.setNamespace(namespace.namespace());
        row.setStatus("ACTIVE");
        row.setVerification("VERIFIED");
        row.setRevision(1);
        row.setSourceReference(source);
        protect(row, text(raw, 100, "studentNumber"));
        one(persons.insertIdentifier(row));
        one(persons.saveIdentifierVersion(row));
        String receipt = uuid(), event = uuid();
        journal.event(actor, person, "IDENTIFIER", row.getId(), 1, event, "commitPartyRosterImport.identifier", "Verified roster import", "Verified roster source", null, version(row), receipt, now);
        journal.complete(actor, "commitPartyRosterImport.identifier", source, key, List.of(person, namespace.namespace(), raw), receipt, event,
                journal.result(identifier(row), 201, "/v1/admin/persons/" + person + "/identifiers/" + row.getId()), now);
    }

    static String version(ManagedPersonRows.Identifier row) {
        return "identifier:" + row.getId() + ":" + row.getRevision();
    }

    static Identifier identifier(ManagedPersonRows.Identifier row) {
        return new Identifier(row.getId(), row.getPersonId(), row.getIdentifierType(), row.getNamespace(), row.getValueMasked(), row.getVerification(), row.getStatus(), version(row), row.getSourceReference());
    }

    private static RuntimeException identifierConflict() {
        return problem(HttpStatus.CONFLICT, "person-identifier-conflict");
    }
}
