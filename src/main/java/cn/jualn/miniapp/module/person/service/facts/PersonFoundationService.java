package cn.jualn.miniapp.module.person.service.facts;

import cn.jualn.miniapp.module.person.service.access.PersonAuthentication;
import cn.jualn.miniapp.module.person.service.profile.ManagedPersonService;
import cn.jualn.miniapp.module.person.service.support.PersonnelJournal;
import cn.jualn.miniapp.module.person.service.support.PersonnelValues;
import org.springframework.transaction.annotation.Isolation;
import cn.jualn.miniapp.common.web.AdminQuery;
import cn.jualn.miniapp.common.web.NameReadBO;
import cn.jualn.miniapp.common.web.NameRow;
import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.module.person.bo.PersonBO.Action;
import cn.jualn.miniapp.module.person.bo.PersonBO.ActionSet;
import cn.jualn.miniapp.module.person.bo.PersonBO.ChangeKind;
import cn.jualn.miniapp.module.person.bo.PersonBO.Domain;
import cn.jualn.miniapp.module.person.bo.PersonBO.History;
import cn.jualn.miniapp.module.person.bo.PersonBO.Identity;
import cn.jualn.miniapp.module.person.bo.PersonBO.IdentityChange;
import cn.jualn.miniapp.module.person.bo.PersonBO.LocalHistory;
import cn.jualn.miniapp.module.person.bo.PersonBO.LocalMemberChange;
import cn.jualn.miniapp.module.person.bo.PersonBO.LocalMemberKind;
import cn.jualn.miniapp.module.person.bo.PersonBO.LocalMembership;
import cn.jualn.miniapp.module.person.bo.PersonBO.Member;
import cn.jualn.miniapp.module.person.bo.PersonBO.MemberInput;
import cn.jualn.miniapp.module.person.bo.PersonBO.MembershipChange;
import cn.jualn.miniapp.module.person.bo.PersonBO.Memberships;
import cn.jualn.miniapp.module.person.bo.PersonBO.Organization;
import cn.jualn.miniapp.module.person.bo.PersonBO.OrganizationChange;
import cn.jualn.miniapp.module.person.bo.PersonBO.OrganizationMemberPage;
import cn.jualn.miniapp.module.person.bo.PersonBO.Page;
import cn.jualn.miniapp.module.person.bo.PersonBO.Reason;
import cn.jualn.miniapp.module.person.bo.PersonBO.Result;
import cn.jualn.miniapp.module.person.bo.PersonBO.Scope;
import cn.jualn.miniapp.module.person.bo.PersonBO.ScopeChange;
import cn.jualn.miniapp.module.person.bo.PersonBO.Summary;
import cn.jualn.miniapp.module.person.bo.PersonBO.Target;
import cn.jualn.miniapp.module.person.bo.PersonBO.Verification;
import cn.jualn.miniapp.module.person.entity.PersonRows;
import cn.jualn.miniapp.module.person.mapper.PersonMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

import cn.jualn.miniapp.module.person.bo.PersonBO.RosterCompleteness;
import cn.jualn.miniapp.module.person.bo.PersonBO.RosterChange;
import cn.jualn.miniapp.module.person.bo.PersonBO.RosterEvent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static cn.jualn.miniapp.module.person.PersonProblems.conflict;
import static cn.jualn.miniapp.module.person.PersonProblems.denied;
import static cn.jualn.miniapp.module.person.PersonProblems.invalid;
import static cn.jualn.miniapp.module.person.PersonProblems.missing;
import static cn.jualn.miniapp.module.person.PersonProblems.problem;
import static cn.jualn.miniapp.module.person.PersonProblems.unavailable;

/**
 * Single owner of facts, immutable events and permanent command receipts.
 * Actor IDs are obtained by the HTTP/CLI adapter from the authenticated session, never a body field.
 */
@Service
@RequiredArgsConstructor
@Transactional(isolation = Isolation.REPEATABLE_READ)
public class PersonFoundationService {
    @org.springframework.beans.factory.annotation.Autowired
    private cn.jualn.miniapp.module.admin.operation.service.AdminAuditJournal managementAudit;

    private final cn.jualn.miniapp.module.person.service.profile.ManagedPersonAccess profileAccess;
    @org.springframework.beans.factory.annotation.Autowired
    private cn.jualn.miniapp.module.person.service.roster.RosterAccess rosterAccess;
    private final PersonMapper mapper;
    private final cn.jualn.miniapp.module.person.mapper.PersonBusinessMapper business;
    private final cn.jualn.miniapp.module.person.service.access.PersonBusinessScopes businessScopes;
    private final cn.jualn.miniapp.module.person.mapper.ManagedPersonMapper managed;
    private final PersonFactReaders readers;
    private final PersonAuthentication authentication;
    private final PersonCatalog catalog;
    private final PersonnelJournal qualificationJournal;
    private final cn.jualn.miniapp.module.person.mapper.QualificationMapper qualifications;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public Result<RosterCompleteness> rosterCompleteness(long actor, String organization) {
        var c = readers.open(cn.jualn.miniapp.module.person.service.access.PersonAuthorityGate.Access.EXCLUSIVE, actor);
        localAccess(actor, organization, false, c.evaluatedAt());
        var value = currentRoster(actor, organization, c.evaluatedAt());
        return rosterResult(value);
    }

    public Result<RosterCompleteness> changeRosterCompleteness(long actor, String organization, RosterChange command, String key, String match) {
        if (command == null || !Set.of("CONFIRM", "REVOKE").contains(command.action() == null ? "" : command.action()))
            throw invalid("action");
        text(command.membershipVersion(), 128, "membershipVersion", false);
        reason(command.reason(), command.verificationReference());
        key(key);
        var c = readers.open(cn.jualn.miniapp.module.person.service.access.PersonAuthorityGate.Access.EXCLUSIVE, actor);
        var now = c.evaluatedAt();
        localAccess(actor, organization, true, now);
        String op = "changeOrganizationRosterCompleteness";
        var prior = receipt(actor, op, organization, key, command, RosterCompleteness.class);
        if (prior != null) return prior;
        var before = currentRoster(actor, organization, now);
        if (match == null) throw problem(HttpStatus.PRECONDITION_REQUIRED, "precondition-required");
        if (!match.matches("\"[^\"\\r\\n]+\"")) throw invalid("If-Match");
        if (!rosterResult(before).etag().equals(match))
            throw problem(HttpStatus.PRECONDITION_FAILED, "precondition-failed");
        if (!before.membershipVersion().equals(command.membershipVersion()))
            throw problem(HttpStatus.CONFLICT, "person-plan-stale");
        var row = business.completeness(organization);
        if ("CONFIRM".equals(command.action())) {
            if (!"ACTIVE".equals(org(organization).getStatus())) throw conflict();
            if (row == null) {
                row = new PersonRows.RosterCompleteness();
                row.setOrganizationId(organization);
            }
            row.setStatus("COMPLETE");
            row.setConfirmedMembershipVersion(before.membershipVersion());
            row.setConfirmedAt(now);
            row.setConfirmedBy(actor);
            row.setSourceReference(command.verificationReference());
        } else {
            if (row == null || row.getConfirmedAt() == null || "REVOKED".equals(row.getStatus())) throw conflict();
            row.setStatus("REVOKED");
        }
        row.setRevision(row.getRevision() + 1);
        row.setLastChangeId(uuid());
        var after = rosterView(row, organization, before.membershipVersion(), now);
        var result = rosterResult(after);
        persist(actor, op, organization, key, command, row.getLastChangeId(), "ROSTER_COMPLETE", row.getRevision(), command.action(), before, after,
                command.reason(), command.verificationReference(), "LOCAL_MANUAL", null, result, now);
        saveCompleteness(row);
        one(mapper.advanceEpoch(Long.parseLong(c.authorityVersion())));
        final String expected = after.membershipVersion();
        cn.jualn.miniapp.module.person.service.profile.ManagedPersonService.recheckBeforeCommit(() -> {
            var at = mapper.now();
            localAccess(actor, organization, true, at);
            if (!expected.equals(businessScopes.membershipVersion(organization, at)))
                throw problem(HttpStatus.CONFLICT, "person-plan-stale");
        });
        return result;
    }

    public Page<RosterEvent> rosterCompletenessHistory(long actor, String organization, int page, int size) {
        page(page, size);
        var c = readers.open(cn.jualn.miniapp.module.person.service.access.PersonAuthorityGate.Access.EXCLUSIVE, actor);
        localAccess(actor, organization, false, c.evaluatedAt());
        currentRoster(actor, organization, c.evaluatedAt());
        var items = business.rosterHistory(organization, size, (long) (page - 1) * size).stream().map(e -> {
            var value = decode(e.getAfterJson(), RosterCompleteness.class);
            return new RosterEvent(e.getId(), organization, e.getOperation(), e.getRecordedAt().toInstant(ZoneOffset.UTC), value.membershipVersion(),
                    "INVALIDATE".equals(e.getOperation()) ? null : Long.toString(e.getOperatorUserId()), e.getReason(), "INVALIDATE".equals(e.getOperation()) ? null : e.getVerificationReference());
        }).toList();
        return new Page<>(items, page, size, business.rosterHistoryCount(organization));
    }

    private RosterCompleteness currentRoster(long actor, String organization, LocalDateTime at) {
        var row = business.completeness(organization);
        businessScopes.requireCompletenessEvidence(row);
        String version = businessScopes.membershipVersion(organization, at);
        if (row != null && "COMPLETE".equals(row.getStatus()) && (!"ACTIVE".equals(org(organization).getStatus()) || !version.equals(row.getConfirmedMembershipVersion()))) {
            var before = rosterView(row, organization, version, at);
            row.setStatus("INVALIDATED");
            row.setRevision(row.getRevision() + 1);
            row.setLastChangeId(uuid());
            var after = rosterView(row, organization, version, at);
            persist(actor, "invalidateOrganizationRosterCompleteness", organization, uuid(), version, row.getLastChangeId(), "ROSTER_COMPLETE", row.getRevision(), "INVALIDATE", before, after,
                    "Membership lifecycle or organization availability changed", "Automatic lifecycle invalidation", "LOCAL_MANUAL", null, rosterResult(after), at);
            saveCompleteness(row);
        }
        if (row != null && row.getRevision() > 0) {
            var event = mapper.event(row.getLastChangeId());
            if (event == null || !organization.equals(event.getAggregateId()) || event.getAggregateVersion() != row.getRevision())
                throw unavailable();
        }
        return rosterView(row, organization, version, at);
    }

    private RosterCompleteness rosterView(PersonRows.RosterCompleteness row, String organization, String membership, LocalDateTime at) {
        String status = row == null ? "UNCONFIRMED" : row.getStatus();
        String version = hash(organization + ":" + status + ":" + (row == null ? 0 : row.getRevision()) + ":" + membership);
        return new RosterCompleteness(organization, status, "COMPLETE".equals(status) ? "VERIFIED" : "UNKNOWN", membership, version, at.toInstant(ZoneOffset.UTC),
                row == null ? null : row.getConfirmedMembershipVersion(), row == null || row.getConfirmedBy() == null ? null : Long.toString(row.getConfirmedBy()),
                row == null || row.getConfirmedAt() == null ? null : row.getConfirmedAt().toInstant(ZoneOffset.UTC), row == null || row.getConfirmedAt() == null ? null : row.getSourceReference(), row == null ? null : row.getLastChangeId());
    }

    private void saveCompleteness(PersonRows.RosterCompleteness row) {
        int changed = business.saveCompleteness(row);
        if (changed != 1 && changed != 2) throw unavailable();
    }

    private Result<RosterCompleteness> rosterResult(RosterCompleteness value) {
        return new Result<>(value, "\"" + value.version() + "\"", null, 200);
    }

    public RosterCompleteness partyRosterBefore(String organization, LocalDateTime at) {
        var row = business.completeness(organization);
        if (row == null || !"COMPLETE".equals(row.getStatus()) || !businessScopes.hasCompletenessEvidence(row))
            return null;
        var version = businessScopes.membershipVersion(organization, at);
        return version.equals(row.getConfirmedMembershipVersion()) ? rosterView(row, organization, version, at) : null;
    }

    public void finishPartyRoster(long actor, RosterCompleteness before, String changeId,
                                  boolean unresolved, LocalDateTime at) {
        if (before == null || !"COMPLETE".equals(before.status())) return;
        var organization = before.organizationId();
        var row = business.completeness(organization);
        businessScopes.requireCompletenessEvidence(row);
        if (row == null || !before.version().equals(rosterView(row, organization, before.membershipVersion(), at).version()))
            throw problem(HttpStatus.CONFLICT, "person-plan-stale");
        var version = businessScopes.membershipVersion(organization, at);
        if (!unresolved && version.equals(before.membershipVersion())) return;

        var action = unresolved ? "INVALIDATE" : "ADVANCE";
        if (unresolved) row.setStatus("INVALIDATED");
        else row.setConfirmedMembershipVersion(version);
        row.setRevision(row.getRevision() + 1);
        row.setLastChangeId(uuid());
        var after = rosterView(row, organization, version, at);
        persist(actor, "recordPartyRosterMembershipChange", organization, changeId, version,
                row.getLastChangeId(), "ROSTER_COMPLETE", row.getRevision(), action, before, after,
                unresolved ? "Import left unresolved rows" : "Confirmed membership change",
                changeId, "LOCAL_MANUAL", null, rosterResult(after), at);
        saveCompleteness(row);
    }

    public PersonCatalog.Catalog stages(long actor) {
        var c = readers.open(actor);
        capability(actor, Domain.IDENTITY, false, c.evaluatedAt());
        return catalog.value();
    }

    public Result<Identity> identity(long actor, String person) {
        var c = readers.open(actor);
        capability(actor, Domain.IDENTITY, false, c.evaluatedAt());
        person(actor, person, Domain.IDENTITY, Action.READ, c, c.evaluatedAt());
        return result(PersonProjection.identity(person, head(person)), 200, null);
    }

    public Result<Memberships> memberships(long actor, String person) {
        var c = readers.open(actor);
        capability(actor, Domain.ORGANIZATION, false, c.evaluatedAt());
        person(actor, person, Domain.ORGANIZATION, Action.READ, c, c.evaluatedAt());
        return result(PersonProjection.memberships(person, head(person), mapper.memberships(person), c.evaluatedAt()), 200, null);
    }

    public OrganizationMemberPage organizationMembers(long actor, String organization, int page, int size) {
        page(page, size);
        var c = readers.open(actor);
        localAccess(actor, organization, false, c.evaluatedAt());
        if (!"ACTIVE".equals(org(organization).getStatus())) throw unavailable();
        var q = new PersonMapper.OrganizationMembers(organization, c.evaluatedAt(), size, (long) (page - 1) * size);
        try {
            if (mapper.corruptOrganizationMembers(q) != 0) throw unavailable();
            var total = mapper.organizationMemberCount(q);
            return new OrganizationMemberPage(organization, "REGISTERED_CURRENT_MEMBERS", mapper.organizationMembers(q), page, size, total, c.evaluatedAt());
        } catch (org.springframework.dao.DataAccessException failure) {
            throw unavailable();
        }
    }

    public Result<LocalMembership> localMembership(long actor, String person, String organization) {
        var c = readers.open(actor);
        localAccess(actor, organization, false, c.evaluatedAt());
        return result(localProjection(person, organization, head(person), mapper.memberships(person), c.evaluatedAt()), 200, null);
    }

    public Page<LocalHistory> localMembershipHistory(long actor, String person, String organization, int page, int size) {
        page(page, size);
        var c = readers.open(actor);
        localAccess(actor, organization, false, c.evaluatedAt());
        var items = mapper.localHistory(person, organization, size, (long) (page - 1) * size).stream().map(e ->
                new LocalHistory(e.getId(), person, organization, decode(e.getLocalAfterJson(), LocalAudit.class).kind(),
                        decode(e.getLocalBeforeJson(), LocalMembership.class), decode(e.getLocalAfterJson(), LocalAudit.class).fact(),
                        Long.toString(e.getOperatorUserId()), e.getRecordedAt(), e.getReason(), e.getVerificationReference(), e.getSource())).toList();
        return new Page<>(items, page, size, mapper.localHistoryCount(person, organization));
    }

    public Result<LocalMembership> changeLocalMembership(long actor, String person, String organization, LocalMemberChange command, String key, String match) {
        if (command == null || command.kind() == null) throw invalid("kind");
        reason(command.reason(), command.verificationReference());
        key(key);
        var c = readers.open(cn.jualn.miniapp.module.person.service.access.PersonAuthorityGate.Access.EXCLUSIVE, actor);
        var now = c.evaluatedAt();
        self(actor, person);
        localAccess(actor, organization, true, now);
        boolean partyTarget = rosterAccess != null && rosterAccess.canManage(actor, organization, now);
        boolean partyJoin = partyTarget && command.kind() == LocalMemberKind.JOIN
                && hasVisiblePartyMembership(actor, person, organization, now);
        boolean generalWrite = !partyJoin && !(partyTarget && command.kind() == LocalMemberKind.REMOVE);
        if (generalWrite) requireGeneralLocalAccess(actor, organization, true, now);
        var organizationRow = org(organization);
        if (!"ACTIVE".equals(organizationRow.getStatus()))
            throw problem(HttpStatus.CONFLICT, "person-organization-inactive");
        String target = person + ":" + organization, op = "changeOrganizationMemberFact";
        var prior = receipt(actor, op, target, key, command, LocalMembership.class);
        if (prior != null) return prior;
        var h = head(person);
        var rows = new ArrayList<>(mapper.memberships(person));
        var before = localProjection(person, organization, h, rows, now);
        etag(match, before);
        final var evaluatedAt = now;
        var member = rows.stream().filter(m -> organization.equals(m.getOrganizationId()) && !m.getValidFrom().isAfter(evaluatedAt)
                && (m.getValidUntil() == null || m.getValidUntil().isAfter(evaluatedAt))).findFirst().orElse(null);
        if ((command.kind() == LocalMemberKind.JOIN || command.kind() == LocalMemberKind.VERIFY_ABSENT) && member != null
                || (command.kind() == LocalMemberKind.REMOVE || command.kind() == LocalMemberKind.VERIFY_PRESENT) && member == null)
            throw conflict();
        boolean partyChange = partyTarget && (command.kind() == LocalMemberKind.JOIN || command.kind() == LocalMemberKind.REMOVE);
        if (partyChange && command.kind() == LocalMemberKind.JOIN && rows.stream()
                .anyMatch(row -> organization.equals(row.getOrganizationId()) && row.getValidFrom().isAfter(evaluatedAt)))
            throw conflict();
        var rosterBefore = partyChange ? partyRosterBefore(organization, now) : null;
        var result = writeLocalMembership(actor, person, organization, command, key, now, h, rows, before, member);
        var event = mapper.localMembership(person, organization).getLastEventId();
        finishPartyRoster(actor, rosterBefore, event, false, now);
        one(mapper.advanceEpoch(Long.parseLong(c.authorityVersion())));
        now = mapper.now();
        localAccess(actor, organization, true, now);
        if (member != null && member.getValidUntil() != null && !member.getValidUntil().isAfter(now)) throw conflict();
        var finalMember = command.kind() == LocalMemberKind.REMOVE ? null : member;
        ManagedPersonService.recheckBeforeCommit(() -> {
            var at = mapper.now();
            if (generalWrite) requireGeneralLocalAccess(actor, organization, true, at);
            else if (!rosterAccess.canManage(actor, organization, at)
                    || partyJoin && !hasVisiblePartyMembership(actor, person, organization, at)) throw missing();
            self(actor, person);
            if (finalMember != null && finalMember.getValidUntil() != null && !finalMember.getValidUntil().isAfter(at))
                throw conflict();
        });
        return result;
    }

    /**
     * Called within the roster transaction; the batch owns proof maintenance and epoch advancement.
     */
    public void removeReviewedMember(long actor, String organization, String person, String membership,
                                     String reason, String reference, String key, PersonReadContext context) {
        var at = context.evaluatedAt();
        rosterAccess.requireOperation(actor,organization,"REMOVE",at);
        self(actor, person);
        var h = head(person);
        var rows = new ArrayList<>(mapper.memberships(person));
        var before = localProjection(person, organization, h, rows, at);
        if (before.verification() != Verification.VERIFIED || !membership.equals(before.membershipId()))
            throw problem(HttpStatus.CONFLICT, "person-plan-stale");
        var member = rows.stream().filter(row -> membership.equals(row.getId())).findFirst()
                .orElseThrow(() -> problem(HttpStatus.CONFLICT, "person-plan-stale"));
        var command = new LocalMemberChange(LocalMemberKind.REMOVE, reason, reference);
        writeLocalMembership(actor, person, organization, command, key, at, h, rows, before, member);
        ManagedPersonService.recheckBeforeCommit(() -> self(actor, person));
    }

    private Result<LocalMembership> writeLocalMembership(long actor, String person, String organization,
                                                         LocalMemberChange command, String key, LocalDateTime now,
                                                         PersonRows.Head h, ArrayList<PersonRows.Membership> rows,
                                                         LocalMembership before, PersonRows.Membership member) {
        String target = person + ":" + organization;
        String op = "changeOrganizationMemberFact";
        h = mutable(person, h);
        h.setRevision(Math.addExact(h.getRevision(), 1));
        String event = uuid();
        h.setLastChangeId(event);
        h.setMembershipChangeId(event);
        // The complete-set verification state is preserved, including UNKNOWN/DISPUTED.
        var fullBefore = membershipEvidence(person, head(person), rows);
        var ended = new ArrayList<PersonRows.Membership>();
        if (command.kind() == LocalMemberKind.JOIN) {
            for (var old : rows) if (organization.equals(old.getOrganizationId())) ended.add(old);
            rows.removeAll(ended);
            member = new PersonRows.Membership();
            member.setId(uuid());
            member.setPersonId(person);
            member.setOrganizationId(organization);
            member.setValidFrom(now);
            member.setCreatedEventId(event);
            rows.add(member);
        } else if (command.kind() == LocalMemberKind.REMOVE) {
            ended.add(member);
            rows.remove(member);
            member = null;
        } else if (command.kind() == LocalMemberKind.VERIFY_ABSENT) {
            for (var old : rows) if (organization.equals(old.getOrganizationId())) ended.add(old);
            rows.removeAll(ended);
        }
        boolean present = command.kind() == LocalMemberKind.JOIN || command.kind() == LocalMemberKind.VERIFY_PRESENT;
        var after = new LocalMembership(person, organization, Verification.VERIFIED, present ? "PRESENT" : "ABSENT",
                present ? member.getId() : null, present ? member.getValidFrom() : null, Long.toString(h.getRevision()), Long.toString(actor), now);
        var result = result(after, 200, null);
        persist(actor, op, target, key, command, event, "PERSON", h.getRevision(), "LOCAL_MEMBERSHIP_CHANGE", fullBefore, membershipEvidence(person, h, rows), command.reason(), command.verificationReference(), "LOCAL_MANUAL", null, result, now, before);
        for (var old : ended) one(mapper.endMembership(old.getId(), event));
        if (command.kind() == LocalMemberKind.JOIN) one(mapper.insertMembership(member));
        var local = new PersonRows.LocalMembership();
        local.setPersonId(person);
        local.setOrganizationId(organization);
        local.setPresence(after.presence());
        local.setMembershipId(after.membershipId());
        local.setVerifiedBy(actor);
        local.setVerifiedAt(now);
        local.setLastEventId(event);
        int changed = mapper.saveLocalMembership(local);
        if (changed != 1 && changed != 2) throw unavailable();
        saveHead(h);
        return result;
    }

    public RosterCompleteness rosterBeforeReview(long actor, String organization, LocalDateTime at) {
        return currentRoster(actor, organization, at);
    }

    public void confirmReviewedRoster(long actor, RosterCompleteness before, String changeId, String reason,
                                      String reference, LocalDateTime at) {
        String organization = before.organizationId();
        var row = business.completeness(organization);
        if (!before.version().equals(rosterView(row, organization, before.membershipVersion(), at).version()))
            throw problem(HttpStatus.CONFLICT, "person-plan-stale");
        if (row == null) {
            row = new PersonRows.RosterCompleteness();
            row.setOrganizationId(organization);
        }
        var version = businessScopes.membershipVersion(organization, at);
        row.setStatus("COMPLETE");
        row.setConfirmedMembershipVersion(version);
        row.setConfirmedAt(at);
        row.setConfirmedBy(actor);
        row.setSourceReference(reference);
        row.setRevision(row.getRevision() + 1);
        row.setLastChangeId(uuid());
        var after = rosterView(row, organization, version, at);
        persist(actor, "confirmReviewedPartyRoster", organization, changeId, version, row.getLastChangeId(),
                "ROSTER_COMPLETE", row.getRevision(), "CONFIRM", before, after, reason, reference,
                "LOCAL_MANUAL", null, rosterResult(after), at);
        saveCompleteness(row);
        ManagedPersonService.recheckBeforeCommit(() -> {
            if (!version.equals(businessScopes.membershipVersion(organization, mapper.now())))
                throw problem(HttpStatus.CONFLICT, "person-plan-stale");
        });
    }

    private void localAccess(long actor, String organization, boolean write, LocalDateTime now) {
        if (rosterAccess != null && (write ? rosterAccess.canManage(actor,organization,now) : rosterAccess.canRead(actor,organization,now))) return;
        requireGeneralLocalAccess(actor, organization, write, now);
    }

    private void requireGeneralLocalAccess(long actor, String organization, boolean write, LocalDateTime now) {
        capability(actor, Domain.ORGANIZATION, false, now);
        if (write) capability(actor, Domain.ORGANIZATION, true, now);
        if (!readers.localOrganizationDuty(actor, organization, write, now))
            organizationScope(actor, organization, write ? Action.MAINTAIN : Action.READ, now);
        org(organization);
    }

    private boolean hasVisiblePartyMembership(long actor, String person, String target, LocalDateTime at) {
        var profile = managed.profile(person);
        if (profile == null || !"ACTIVE".equals(profile.getStatus())) return false;
        return mapper.memberships(person).stream().anyMatch(member ->
                !target.equals(member.getOrganizationId()) && !member.getValidFrom().isAfter(at)
                        && (member.getValidUntil() == null || member.getValidUntil().isAfter(at))
                        && rosterAccess.canManage(actor, member.getOrganizationId(), at));
    }

    private LocalMembership localProjection(String person, String organization, PersonRows.Head h, List<PersonRows.Membership> rows, LocalDateTime now) {
        return PersonProjection.localMembership(person, organization, h, mapper.localMembership(person, organization), rows, now);
    }

    /**
     * Internal complete storage snapshot, never returned by the local HTTP/history projection.
     */
    private Memberships membershipEvidence(String person, PersonRows.Head h, List<PersonRows.Membership> rows) {
        return new Memberships(person, h == null ? Verification.UNKNOWN : Verification.valueOf(h.getMembershipVerification()),
                rows.stream().sorted(Comparator.comparing(PersonRows.Membership::getOrganizationId)).map(m -> new Member(m.getId(), m.getOrganizationId(), m.getValidFrom(), m.getValidUntil())).toList(),
                h == null ? "0" : Long.toString(h.getRevision()), h == null ? null : h.getLastChangeId(), h == null || h.getMembershipVerifiedBy() == null ? null : Long.toString(h.getMembershipVerifiedBy()), h == null ? null : h.getMembershipVerifiedAt(),
                null, null);
    }

    private record LocalAudit(String kind, LocalMembership fact) {
    }

    public Page<Summary> persons(long actor, int page, int size, String organizationId) {
        return persons(actor, page, size, organizationId, null, null);
    }

    public Page<Summary> persons(long actor, int page, int size, String organizationId, String name, String status) {
        page(page, size);
        if (name != null) text(name, 100, "q", false);
        if (status != null && !Set.of("ACTIVE", "ARCHIVED").contains(status)) throw invalid("status");
        var c = readers.open(actor);
        capability(actor, Domain.IDENTITY, false, c.evaluatedAt());
        capability(actor, Domain.ORGANIZATION, false, c.evaluatedAt());
        for (var d : List.of(Domain.IDENTITY, Domain.ORGANIZATION)) {
            var plan = readers.plan(actor, d, Action.READ, organizationId, c);
            queryReady(plan);
        }
        var q = readers.query(actor, List.of(new PersonMapper.Check("IDENTITY", "READ"), new PersonMapper.Check("ORGANIZATION", "READ")), c.evaluatedAt(), null, organizationId, size, (long) (page - 1) * size);
        q = q.withFilters(name == null ? null : name.strip(), status);
        var items = mapper.personPage(q).stream().map(s -> {
            boolean fieldReadable = true;
            try {
                profileAccess.field(actor, s.personId(), organizationId, false, c.evaluatedAt());
            } catch (ContractProblemException e) {
                if (e.getStatus() != HttpStatus.NOT_FOUND && e.getStatus() != HttpStatus.FORBIDDEN) throw e;
                fieldReadable = false;
            }
            var registration = fieldReadable ? managed.registration(s.personId()) : null;
            String disambiguation = registration != null && "SET".equals(registration.getLastAction()) ? registration.getMajorClass() : null;
            return new Summary(s.personId(), s.realName(), s.status(), s.identityVerification(), s.membershipVerification(), disambiguation);
        }).toList();
        return new Page<>(items, page, size, mapper.personCount(q));
    }

    public Page<History<Identity>> identityHistory(long actor, String person, int page, int size) {
        page(page, size);
        var c = readers.open(actor);
        capability(actor, Domain.IDENTITY, false, c.evaluatedAt());
        person(actor, person, Domain.IDENTITY, Action.READ, c, c.evaluatedAt());
        return new Page<>(mapper.history(person, true, size, (long) (page - 1) * size).stream().map(e -> history(e, Identity.class)).toList(), page, size, mapper.historyCount(person, true));
    }

    public Page<History<Memberships>> membershipHistory(long actor, String person, int page, int size) {
        page(page, size);
        var c = readers.open(actor);
        capability(actor, Domain.ORGANIZATION, false, c.evaluatedAt());
        person(actor, person, Domain.ORGANIZATION, Action.READ, c, c.evaluatedAt());
        return new Page<>(mapper.history(person, false, size, (long) (page - 1) * size).stream().map(e -> history(e, Memberships.class)).toList(), page, size, mapper.historyCount(person, false));
    }

    public Result<Identity> changeIdentity(long actor, String person, IdentityChange command, String key, String match) {
        validate(command);
        key(key);
        var c = readers.open(cn.jualn.miniapp.module.person.service.access.PersonAuthorityGate.Access.EXCLUSIVE, actor);
        var now = c.evaluatedAt();
        capability(actor, Domain.IDENTITY, true, now);
        self(actor, person);
        person(actor, person, Domain.IDENTITY, Action.MAINTAIN, c, now);
        String op = "changePersonIdentity", target = person;
        var prior = receipt(actor, op, target, key, command, Identity.class);
        if (prior != null) return prior;
        etag(match, PersonProjection.identity(person, head(person)));
        var result = writeIdentity(actor, person, command, key, now);
        one(mapper.advanceEpoch(Long.parseLong(c.authorityVersion())));
        capability(actor, Domain.IDENTITY, true, mapper.now());
        now = mapper.now();
        person(actor, person, Domain.IDENTITY, Action.MAINTAIN, c, now);
        future(command.validUntil(), now);
        var completedCommand = command;
        ManagedPersonService.recheckBeforeCommit(() -> {
            var at = mapper.now();
            capability(actor, Domain.IDENTITY, true, at);
            person(actor, person, Domain.IDENTITY, Action.MAINTAIN, c, at);
            future(completedCommand.validUntil(), at);
        });
        return result;
    }

    public void correctStageFromPartyPlan(long actor, String person, String stage, String reason, String reference,
                                          String key, PersonReadContext context) {
        context.requireUser(actor);
        var h = head(person);
        if (h == null || !"VERIFIED".equals(h.getIdentityVerification())) throw conflict();
        boolean absent = "NONE".equals(h.getIdentityPresence());
        var command = new IdentityChange(absent ? ChangeKind.RECORD : ChangeKind.CORRECT, stage,
                h.getIdentityValidUntil(), absent ? null : h.getLastChangeId(), reason, reference);
        writeIdentity(actor, person, command, key, context.evaluatedAt());
        ManagedPersonService.recheckBeforeCommit(() -> future(command.validUntil(), mapper.now()));
    }

    private Result<Identity> writeIdentity(long actor, String person, IdentityChange command, String key, LocalDateTime now) {
        String op = "changePersonIdentity", target = person;
        var h = head(person);
        var before = PersonProjection.identity(person, h);
        var active = before.currentRecord() != null && "ACTIVE".equals(before.currentRecord().status())
                && (before.currentRecord().validUntil() == null || before.currentRecord().validUntil().isAfter(now));
        if (Set.of(ChangeKind.RECORD, ChangeKind.TRANSITION, ChangeKind.CORRECT).contains(command.kind())) {
            if (!catalog.confirmed(command.stageId())) throw problem(HttpStatus.CONFLICT, "person-stage-unconfirmed");
            if (command.kind() == ChangeKind.RECORD && active || command.kind() == ChangeKind.TRANSITION && (!active || Objects.equals(command.stageId(), before.currentRecord().stageId())))
                throw conflict();
            if (command.kind() == ChangeKind.CORRECT && (h == null || !Objects.equals(command.correctsChangeId(), h.getLastChangeId())))
                throw conflict();
            future(command.validUntil(), now);
        }
        if (Set.of(ChangeKind.INVALIDATE, ChangeKind.REVOKE).contains(command.kind()) && !active) throw conflict();
        if (command.kind() == ChangeKind.VERIFY_NONE && active) throw conflict();
        h = mutable(person, h);
        String event = uuid();
        h.setRevision(Math.addExact(h.getRevision(), 1));
        h.setLastChangeId(event);
        h.setIdentityChangeId(event);
        switch (command.kind()) {
            case RECORD, TRANSITION, CORRECT -> {
                clearIdentity(h);
                h.setIdentityVerification("VERIFIED");
                h.setIdentityPresence("RECORDED");
                h.setCurrentRecordId(event);
                h.setStageId(command.stageId());
                h.setIdentityStatus("ACTIVE");
                h.setIdentityValidFrom(now);
                h.setIdentityValidUntil(command.validUntil());
            }
            case INVALIDATE, REVOKE ->
                    h.setIdentityStatus(command.kind() == ChangeKind.REVOKE ? "REVOKED" : "INACTIVE");
            case VERIFY_NONE -> {
                clearIdentity(h);
                h.setIdentityVerification("VERIFIED");
                h.setIdentityPresence("NONE");
            }
            case MARK_UNKNOWN, MARK_DISPUTED -> {
                clearIdentity(h);
                h.setIdentityVerification(command.kind() == ChangeKind.MARK_UNKNOWN ? "UNKNOWN" : "DISPUTED");
            }
        }
        if ("VERIFIED".equals(h.getIdentityVerification())) {
            h.setIdentityVerifiedBy(actor);
            h.setIdentityVerifiedAt(now);
        }
        var after = PersonProjection.identity(person, h);
        var result = result(after, 200, null);
        persist(actor, op, target, key, command, event, "PERSON", h.getRevision(), command.kind().name(), before, after, command.reason(), command.verificationReference(), "LOCAL_MANUAL", command.correctsChangeId(), result, now);
        saveHead(h);
        return result;
    }

    /**
     * Limited self-service roster claim; never calls an administrator mutation or changes its policy.
     * Caller has the exclusive Person gate, current account locks and the verified source row.
     */
    public void initializeRosterPerson(long operator, String person, String organization, String stage, String source, long sourceVersion, long approver, String key, boolean newPerson, PersonReadContext c) {
        c.requirePerson(person);
        var now = c.evaluatedAt();
        var h = head(person);
        if (stage != null && !catalog.confirmed(stage) || !"ACTIVE".equals(org(organization).getStatus()))
            throw PersonnelValues.conflict();
        var identity = PersonProjection.identity(person, h);
        if (newPerson && stage != null) {
            if (h != null && (!"UNKNOWN".equals(h.getIdentityVerification()) || h.getCurrentRecordId() != null)
                    || mapper.historyCount(person, true) != 0) throw PersonnelValues.conflict();
            h = mutable(person, h);
            h.setRevision(Math.addExact(h.getRevision(), 1));
            String event = uuid();
            h.setLastChangeId(event);
            h.setIdentityChangeId(event);
            h.setIdentityVerification("VERIFIED");
            h.setIdentityPresence("RECORDED");
            h.setCurrentRecordId(event);
            h.setStageId(stage);
            h.setIdentityStatus("ACTIVE");
            h.setIdentityValidFrom(now);
            h.setIdentityValidUntil(null);
            h.setIdentityVerifiedBy(approver);
            h.setIdentityVerifiedAt(now);
            qualificationJournal.append(operator, "ROSTER_IDENTITY", person, key, source, event, "PERSON", h.getRevision(), identity,
                    PersonProjection.identity(person, h), "Verified roster import", source, "ROSTER_IMPORT", source, sourceVersion, approver, now, 200, null);
            saveHead(h);
        }
        h = mutable(person, h);
        var rows = new ArrayList<>(mapper.memberships(person));
        var before = membershipEvidence(person, h, rows);
        var localBefore = localProjection(person, organization, h, rows, now);
        var member = rows.stream().filter(m -> organization.equals(m.getOrganizationId()) && !m.getValidFrom().isAfter(now)
                && (m.getValidUntil() == null || m.getValidUntil().isAfter(now))).findFirst().orElse(null);
        if (member == null && rows.stream().anyMatch(m -> organization.equals(m.getOrganizationId()) && m.getValidFrom().isAfter(now)))
            throw PersonnelValues.conflict();
        if (member != null) return; // Reuse the existing formal membership without re-verifying it.
        h.setRevision(Math.addExact(h.getRevision(), 1));
        String event = uuid();
        h.setLastChangeId(event);
        h.setMembershipChangeId(event);
        boolean added = member == null;
        var ended = new ArrayList<PersonRows.Membership>();
        if (added) {
            for (var old : rows) if (organization.equals(old.getOrganizationId())) ended.add(old);
            rows.removeAll(ended);
            member = new PersonRows.Membership();
            member.setId(uuid());
            member.setPersonId(person);
            member.setOrganizationId(organization);
            member.setValidFrom(now);
            member.setCreatedEventId(event);
            rows.add(member);
        }
        var fact = new LocalMembership(person, organization, Verification.VERIFIED, "PRESENT", member.getId(), member.getValidFrom(),
                Long.toString(h.getRevision()), Long.toString(approver), now);
        qualificationJournal.append(operator, "ROSTER_MEMBERSHIP", person, key, source, event, "PERSON", h.getRevision(), before, membershipEvidence(person, h, rows),
                "Verified roster import", source, "ROSTER_IMPORT", source, sourceVersion, approver, now, 200, null, organization, localBefore, new LocalAudit(added ? "JOIN" : "VERIFY_PRESENT", fact));
        for (var old : ended) one(mapper.endMembership(old.getId(), event));
        if (added) one(mapper.insertMembership(member));
        var positive = new PersonRows.LocalMembership();
        positive.setPersonId(person);
        positive.setOrganizationId(organization);
        positive.setPresence("PRESENT");
        positive.setMembershipId(member.getId());
        positive.setVerifiedBy(approver);
        positive.setVerifiedAt(now);
        positive.setLastEventId(event);
        int count = mapper.saveLocalMembership(positive);
        if (count != 1 && count != 2) throw unavailable();
        saveHead(h);
    }

    public Result<Memberships> replaceMemberships(long actor, String person, MembershipChange command, String key, String match) {
        validate(command);
        key(key);
        var c = readers.open(cn.jualn.miniapp.module.person.service.access.PersonAuthorityGate.Access.EXCLUSIVE, actor);
        var now = c.evaluatedAt();
        capability(actor, Domain.ORGANIZATION, true, now);
        self(actor, person);
        person(actor, person, Domain.ORGANIZATION, Action.MAINTAIN, c, now);
        var old = mapper.memberships(person);
        var union = new TreeSet<String>();
        old.forEach(m -> union.add(m.getOrganizationId()));
        if (command.memberships() != null) command.memberships().forEach(m -> union.add(m.organizationId()));
        requireMemberUnion(actor, person, union, now);
        String op = "replacePersonMemberships", target = person;
        var prior = receipt(actor, op, target, key, command, Memberships.class);
        if (prior != null) return prior;
        var h = head(person);
        var before = PersonProjection.memberships(person, h, old, now);
        etag(match, before);
        if (command.memberships() != null) for (var m : command.memberships()) {
            var org = mapper.organization(m.organizationId());
            if (org == null) throw missing();
            if (!"ACTIVE".equals(org.getStatus())) throw problem(HttpStatus.CONFLICT, "person-organization-inactive");
            future(m.validUntil(), now);
        }
        h = mutable(person, h);
        h.setRevision(Math.addExact(h.getRevision(), 1));
        String event = uuid();
        h.setLastChangeId(event);
        h.setMembershipChangeId(event);
        h.setMembershipVerification(command.verification().name());
        h.setMembershipVerifiedBy(command.verification() == Verification.VERIFIED ? actor : null);
        h.setMembershipVerifiedAt(command.verification() == Verification.VERIFIED ? now : null);
        var keep = new ArrayList<PersonRows.Membership>();
        var ended = new ArrayList<PersonRows.Membership>();
        var added = new ArrayList<PersonRows.Membership>();
        var desired = command.memberships() == null ? List.<MemberInput>of() : command.memberships();
        for (var previous : old) {
            var requested = desired.stream().filter(m -> m.organizationId().equals(previous.getOrganizationId())).findFirst().orElse(null);
            if (requested != null && Objects.equals(requested.validUntil(), previous.getValidUntil()) && (previous.getValidUntil() == null || previous.getValidUntil().isAfter(now)))
                keep.add(previous);
            else ended.add(previous);
        }
        for (var m : desired)
            if (keep.stream().noneMatch(k -> k.getOrganizationId().equals(m.organizationId()))) {
                var row = new PersonRows.Membership();
                row.setId(uuid());
                row.setPersonId(person);
                row.setOrganizationId(m.organizationId());
                row.setValidFrom(now);
                row.setValidUntil(m.validUntil());
                row.setCreatedEventId(event);
                added.add(row);
            }
        var all = new ArrayList<>(keep);
        all.addAll(added);
        var after = PersonProjection.memberships(person, h, all, now);
        var result = result(after, 200, null);
        persist(actor, op, target, key, command, event, "PERSON", h.getRevision(), "REPLACE_MEMBERSHIPS", before, after, command.reason(), command.verificationReference(), "LOCAL_MANUAL", null, result, now);
        mapper.clearLocalMemberships(person);
        for (var row : ended) one(mapper.endMembership(row.getId(), event));
        for (var row : added) one(mapper.insertMembership(row));
        saveHead(h);
        one(mapper.advanceEpoch(Long.parseLong(c.authorityVersion())));
        capability(actor, Domain.ORGANIZATION, true, mapper.now());
        now = mapper.now();
        requireMemberUnion(actor, person, union, now);
        for (var m : desired) future(m.validUntil(), now);
        ManagedPersonService.recheckBeforeCommit(() -> {
            var at = mapper.now();
            capability(actor, Domain.ORGANIZATION, true, at);
            requireMemberUnion(actor, person, union, at);
            for (var m : desired) future(m.validUntil(), at);
        });
        return result;
    }

    public Page<Organization> organizations(long actor, int page, int size, String status) {
        page(page, size);
        if (status != null && !Set.of("ACTIVE", "INACTIVE").contains(status)) throw invalid("status");
        var c = readers.open(actor);
        capability(actor, Domain.ORGANIZATION, false, c.evaluatedAt());
        if (readers.localOrganizationDuties(actor, c.evaluatedAt()).isEmpty() && !readers.rootOwner(actor, c.evaluatedAt()) && (!readers.hasOperate(actor, Domain.ORGANIZATION, Action.READ, c.evaluatedAt()) || scopes(actor).stream().noneMatch(s -> "OPERATE".equals(s.getMode()) && "ORGANIZATION".equals(s.getDomain()) && "ORGANIZATION".equals(s.getTargetType()) && PersonFactReaders.actions(s).contains(Action.READ) && effective(s, c.evaluatedAt()))))
            throw denied();
        return new Page<>(mapper.organizationPage(readers.localOrganizationDuties(actor, c.evaluatedAt()), actor, readers.rootOwner(actor, c.evaluatedAt()), c.evaluatedAt(), status, size, (long) (page - 1) * size).stream().map(PersonProjection::organization).toList(), page, size, mapper.organizationCount(readers.localOrganizationDuties(actor, c.evaluatedAt()), actor, readers.rootOwner(actor, c.evaluatedAt()), c.evaluatedAt(), status));
    }

    public Result<Organization> organization(long actor, String id) {
        var c = readers.open(actor);
        capability(actor, Domain.ORGANIZATION, false, c.evaluatedAt());
        organizationScope(actor, id, Action.READ, c.evaluatedAt());
        return result(PersonProjection.organization(org(id)), 200, null);
    }

    public List<NameReadBO> organizationNames(long actor, List<String> ids) {
        var c = readers.open(actor);
        capability(actor, Domain.ORGANIZATION, false, c.evaluatedAt());
        var local = readers.localOrganizationDuties(actor, c.evaluatedAt());
        boolean root = readers.rootOwner(actor, c.evaluatedAt());
        var sources = root ? List.<PersonRows.Scope>of() : scopes(actor);
        var results = new LinkedHashMap<String, NameReadBO>();
        var allowed = new ArrayList<String>();
        for (var id : ids) {
            try {
                if (root || local.contains(id) || sources.stream().anyMatch(s -> "OPERATE".equals(s.getMode())
                        && "ORGANIZATION".equals(s.getDomain()) && "ORGANIZATION".equals(s.getTargetType())
                        && id.equals(s.getTargetId()) && PersonFactReaders.actions(s).contains(Action.READ) && effective(s, c.evaluatedAt()))) {
                    allowed.add(id);
                } else {
                    results.put(id, new NameReadBO(id, "UNAVAILABLE", null, null));
                }
            } catch (RuntimeException failure) {
                results.put(id, new NameReadBO(id, "ERROR", null, AdminQuery.problem(failure)));
            }
        }
        if (!allowed.isEmpty()) {
            try {
                var names = mapper.organizationNames(allowed).stream().collect(java.util.stream.Collectors.toMap(
                        NameRow::id, java.util.function.Function.identity()));
                for (var id : allowed) {
                    var row = names.get(id);
                    results.put(id, new NameReadBO(id, row == null ? "UNAVAILABLE" : "READY", row == null ? null : row.name(), null));
                }
            } catch (RuntimeException failure) {
                var problem = AdminQuery.problem(failure);
                for (var id : allowed) {
                    results.put(id, new NameReadBO(id, "ERROR", null, problem));
                }
            }
        }
        return ids.stream().map(results::get).toList();
    }

    public Result<Organization> createOrganization(long actor, OrganizationChange command, String key) {
        validate(command, true);
        key(key);
        var c = readers.open(cn.jualn.miniapp.module.person.service.access.PersonAuthorityGate.Access.EXCLUSIVE, actor);
        var now = c.evaluatedAt();
        capability(actor, Domain.ORGANIZATION, true, now);
        directory(actor, now);
        String op = "createPersonOrganization";
        var prior = receipt(actor, op, "DIRECTORY", key, command, Organization.class);
        if (prior != null) return prior;
        var row = new PersonRows.Organization();
        row.setId(uuid());
        row.setName(command.name());
        row.setType(command.type());
        row.setDescription(command.description());
        row.setStatus("ACTIVE");
        row.setRevision(1);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        row.setLastChangeId(uuid());
        var after = PersonProjection.organization(row);
        var result = result(after, 201, "/v1/admin/organizations/" + row.getId());
        persist(actor, op, "DIRECTORY", key, command, row.getLastChangeId(), "ORGANIZATION", 1, "CREATE", null, after, command.reason(), command.verificationReference(), "LOCAL_MANUAL", null, result, now);
        one(mapper.insertOrganization(row));
        one(mapper.advanceEpoch(Long.parseLong(c.authorityVersion())));
        capability(actor, Domain.ORGANIZATION, true, mapper.now());
        now = mapper.now();
        directory(actor, now);
        return result;
    }

    public Result<Organization> updateOrganization(long actor, String id, OrganizationChange command, String key, String match) {
        validate(command, false);
        key(key);
        var c = readers.open(cn.jualn.miniapp.module.person.service.access.PersonAuthorityGate.Access.EXCLUSIVE, actor);
        var now = c.evaluatedAt();
        capability(actor, Domain.ORGANIZATION, true, now);
        organizationScope(actor, id, Action.MAINTAIN, now);
        var row = org(id);
        String op = "updatePersonOrganization";
        var prior = receipt(actor, op, id, key, command, Organization.class);
        if (prior != null) return prior;
        var before = PersonProjection.organization(row);
        etag(match, before);
        if (command.type() != null) row.setType(command.type());
        if (command.name() != null) row.setName(command.name());
        if (command.description() != null) row.setDescription(command.description());
        if (command.status() != null) row.setStatus(command.status());
        row.setRevision(Math.addExact(row.getRevision(), 1));
        row.setUpdatedAt(now);
        row.setLastChangeId(uuid());
        var after = PersonProjection.organization(row);
        var result = result(after, 200, null);
        persist(actor, op, id, key, command, row.getLastChangeId(), "ORGANIZATION", row.getRevision(), "EDIT", before, after, command.reason(), command.verificationReference(), "LOCAL_MANUAL", null, result, now);
        one(mapper.updateOrganization(row));
        one(mapper.advanceEpoch(Long.parseLong(c.authorityVersion())));
        capability(actor, Domain.ORGANIZATION, true, mapper.now());
        now = mapper.now();
        organizationScope(actor, id, Action.MAINTAIN, now);
        return result;
    }

    public Page<Scope> scopes(long actor, int page, int size, Long grantee, Domain domain) {
        page(page, size);
        var c = readers.open(actor);
        scopeCapability(actor, false, c.evaluatedAt());
        controlPresent(actor, c.evaluatedAt());
        return new Page<>(mapper.scopePage(actor, readers.rootOwner(actor, c.evaluatedAt()), c.evaluatedAt(), grantee, domain == null ? null : domain.name(), size, (long) (page - 1) * size).stream().map(s -> PersonProjection.scope(s, c.evaluatedAt())).toList(), page, size, mapper.scopeCount(actor, readers.rootOwner(actor, c.evaluatedAt()), c.evaluatedAt(), grantee, domain == null ? null : domain.name()));
    }

    public Result<Scope> scope(long actor, String id) {
        var c = readers.open(actor);
        scopeCapability(actor, false, c.evaluatedAt());
        var row = scopeRow(id);
        control(actor, row, c.evaluatedAt());
        return result(PersonProjection.scope(row, c.evaluatedAt()), 200, null);
    }

    public Result<Scope> grant(long actor, ScopeChange command, String key) {
        validate(command);
        key(key);
        long grantee = userId(command.granteeUserId());
        long[] users = new long[]{actor, grantee};
        var c = readers.open(cn.jualn.miniapp.module.person.service.access.PersonAuthorityGate.Access.EXCLUSIVE, users);
        var now = c.evaluatedAt();
        scopeCapability(actor, true, now);
        selfAccount(actor, grantee);
        var row = new PersonRows.Scope();
        row.setId(uuid());
        row.setGranteeUserId(grantee);
        row.setMode("OPERATE");
        row.setDomain(command.actionSet().domain().name());
        row.setActions(actionKey(command.actionSet()));
        row.setTargetType(command.target().type());
        row.setTargetId(targetId(command.target()));
        var authority = control(actor, row, now);
        row.setAuthorityGrantId(authority == null ? null : authority.getId());
        if ("ORGANIZATION".equals(row.getTargetType())) org(row.getTargetId());
        if ("PERSON".equals(row.getTargetType()) && managed.profile(row.getTargetId()) == null) throw missing();
        String op = "grantPersonManagementScope", target = command.granteeUserId() + ":" + row.getDomain() + ":" + row.getTargetType() + ":" + row.getTargetId();
        var prior = receipt(actor, op, target, key, command, Scope.class);
        if (prior != null) return prior;
        future(command.validUntil(), now);
        for (var s : mapper.actorScopes(grantee))
            if (s.getMode().equals(row.getMode()) && s.getDomain().equals(row.getDomain()) && s.getTargetType().equals(row.getTargetType()) && s.getTargetId().equals(row.getTargetId()) && s.getActions().equals(row.getActions()) && PersonFactReaders.live(s, now))
                throw conflict();
        mapper.releaseExpiredScopes(now);
        row.setValidFrom(now);
        row.setValidUntil(command.validUntil());
        row.setGrantedBy(actor);
        row.setReason(command.reason());
        row.setVerificationReference(command.verificationReference());
        row.setSource(authority == null ? "ROOT_OWNER" : "LOCAL_MAINTENANCE");
        row.setRevision(1);
        row.setLastEventId(uuid());
        row.setCurrentSlot(1);
        var after = PersonProjection.scope(row, now);
        var result = result(after, 201, "/v1/admin/person-management-scopes/" + row.getId());
        persist(actor, op, target, key, command, row.getLastEventId(), "SCOPE", 1, "GRANT", null, after, command.reason(), command.verificationReference(), row.getSource(), null, result, now);
        one(mapper.insertScope(row));
        one(mapper.advanceEpoch(Long.parseLong(c.authorityVersion())));
        scopeCapability(actor, true, mapper.now());
        now = mapper.now();
        control(actor, row, now);
        future(command.validUntil(), now);
        return result;
    }

    public Result<Scope> revoke(long actor, String id, Reason command, String key, String match) {
        reason(command.reason(), command.verificationReference());
        key(key);
        var c = readers.open(cn.jualn.miniapp.module.person.service.access.PersonAuthorityGate.Access.EXCLUSIVE, actor);
        var now = c.evaluatedAt();
        scopeCapability(actor, true, now);
        var row = scopeRow(id);
        selfAccount(actor, row.getGranteeUserId());
        control(actor, row, now);
        String op = "revokePersonManagementScope";
        var prior = receipt(actor, op, id, key, command, Scope.class);
        if (prior != null) return prior;
        var before = PersonProjection.scope(row, now);
        etag(match, before);
        if (!PersonFactReaders.live(row, now)) throw conflict();
        row.setRevokedAt(now);
        row.setRevokedBy(actor);
        row.setRevokeReason(command.reason());
        row.setRevision(Math.addExact(row.getRevision(), 1));
        row.setLastEventId(uuid());
        row.setCurrentSlot(null);
        var after = PersonProjection.scope(row, now);
        var result = result(after, 200, null);
        persist(actor, op, id, key, command, row.getLastEventId(), "SCOPE", row.getRevision(), "REVOKE", before, after, command.reason(), command.verificationReference(), row.getSource(), null, result, now);
        one(mapper.revokeScope(row));
        one(mapper.advanceEpoch(Long.parseLong(c.authorityVersion())));
        scopeCapability(actor, true, mapper.now());
        now = mapper.now();
        control(actor, row, now);
        return result;
    }

    private void capability(long actor, Domain domain, boolean write, LocalDateTime at) {
        authentication.require(actor, domain.name(), domain == Domain.IDENTITY ? (write ? "identity:map-manage" : "identity:read") : (write ? "organization:map-manage" : "organization:read"), at);
    }

    private void scopeCapability(long actor, boolean write, LocalDateTime at) {
        authentication.require(actor, "ORGANIZATION", write ? "person-scope:manage" : "person-scope:read", at);
    }

    private void person(long actor, String person, Domain domain, Action action, PersonReadContext c, LocalDateTime now) {
        var decision = readers.authorizeAt(actor, person, domain, action, c, now);
        if (decision.decision() == PersonFactReaders.Decision.INDETERMINATE) throw unavailable();
        if (decision.decision() != PersonFactReaders.Decision.ALLOW) throw missing();
    }

    private void queryReady(PersonFactReaders.QueryResult result) {
        if ("DENIED".equals(result.decision())) throw denied();
        if (!"READY".equals(result.decision())) throw unavailable();
    }

    private PersonRows.Head head(String person) {
        if (mapper.corruptPerson(person) != 0) throw unavailable();
        return mapper.head(person);
    }

    private PersonRows.Organization org(String id) {
        var r = mapper.organization(id);
        if (r == null) throw missing();
        return r;
    }

    private PersonRows.Scope scopeRow(String id) {
        var r = mapper.scope(id);
        if (r == null || !"OPERATE".equals(r.getMode())) throw missing();
        if (mapper.corruptScopes(r.getGranteeUserId()) != 0) throw unavailable();
        return r;
    }

    private List<PersonRows.Scope> scopes(long actor) {
        if (mapper.corruptScopes(actor) != 0) throw unavailable();
        return mapper.actorScopes(actor);
    }

    private boolean effective(PersonRows.Scope s, LocalDateTime now) {
        return PersonFactReaders.live(s, now) && ("ROOT_OWNER".equals(s.getSource()) || PersonFactReaders.live(mapper.scope(s.getAuthorityGrantId()), now));
    }

    private boolean direct(long actor, String person, Domain domain, Action action, LocalDateTime now) {
        return scopes(actor).stream().anyMatch(s -> "OPERATE".equals(s.getMode()) && domain.name().equals(s.getDomain()) && PersonFactReaders.actions(s).contains(action) && "PERSON".equals(s.getTargetType()) && person.equals(s.getTargetId()) && effective(s, now));
    }

    private void requireMemberUnion(long actor, String person, Set<String> organizations, LocalDateTime now) {
        if (readers.rootOwner(actor, now)) return;
        if (direct(actor, person, Domain.ORGANIZATION, Action.MAINTAIN, now)) return;
        if (organizations.isEmpty()) throw missing();
        if (scopes(actor).stream().noneMatch(s -> "OPERATE".equals(s.getMode()) && "ORGANIZATION".equals(s.getDomain())
                && "ORGANIZATION".equals(s.getTargetType()) && PersonFactReaders.actions(s).contains(Action.MAINTAIN) && effective(s, now)
                && organizations.stream().allMatch(id -> id.equals(s.getTargetId())))) throw missing();
    }

    private void organizationScope(long actor, String organization, Action action, LocalDateTime now) {
        if (action == Action.READ && readers.localOrganizationDuty(actor, organization, now)) return;
        if (readers.rootOwner(actor, now)) return;
        if (scopes(actor).stream().noneMatch(s -> "OPERATE".equals(s.getMode()) && "ORGANIZATION".equals(s.getDomain()) && "ORGANIZATION".equals(s.getTargetType()) && organization.equals(s.getTargetId()) && PersonFactReaders.actions(s).contains(action) && effective(s, now)))
            throw missing();
    }

    private void directory(long actor, LocalDateTime now) {
        if (readers.rootOwner(actor, now)) return;
        if (scopes(actor).stream().noneMatch(s -> "ADMINISTER".equals(s.getMode()) && "ORGANIZATION".equals(s.getDomain()) && "DIRECTORY".equals(s.getTargetType()) && PersonFactReaders.actions(s).contains(Action.MAINTAIN) && PersonFactReaders.live(s, now)))
            throw denied();
    }

    private void controlPresent(long actor, LocalDateTime now) {
        if (readers.rootOwner(actor, now)) return;
        if (scopes(actor).stream().noneMatch(s -> "ADMINISTER".equals(s.getMode()) && PersonFactReaders.live(s, now)))
            throw denied();
    }

    private PersonRows.Scope control(long actor, PersonRows.Scope requested, LocalDateTime now) {
        if (readers.rootOwner(actor, now)) return null;
        return scopes(actor).stream().filter(a -> "ADMINISTER".equals(a.getMode()) && PersonFactReaders.live(a, now) && a.getDomain().equals(requested.getDomain()) && PersonFactReaders.actions(a).containsAll(PersonFactReaders.actions(requested))
                        && (a.getTargetType().equals(requested.getTargetType()) && a.getTargetId().equals(requested.getTargetId()) || "DIRECTORY".equals(a.getTargetType()) && "ORGANIZATION".equals(a.getDomain()) && "ORGANIZATION".equals(requested.getTargetType())))
                .findFirst().orElseThrow(PersonFoundationService::hidden);
    }

    private static RuntimeException hidden() {
        return missing();
    }

    private <T> History<T> history(PersonRows.Event e, Class<T> type) {
        return new History<>(e.getId(), e.getAggregateId(), e.getOperation(), decode(e.getBeforeJson(), type), decode(e.getAfterJson(), type), Long.toString(e.getOperatorUserId()), e.getRecordedAt(), e.getReason(), e.getVerificationReference(), e.getSource());
    }

    private <T> Result<T> receipt(long actor, String operation, String target, String key, Object command, Class<T> type) {
        var r = mapper.receipt(actor, operation, target, key);
        if (r == null) return null;
        if (!fingerprint(command).equals(r.getFingerprint()))
            throw problem(HttpStatus.CONFLICT, "person-idempotency-conflict");
        var event = mapper.event(r.getEventId());
        if (event == null || !r.getId().equals(event.getReceiptId())) throw unavailable();
        return new Result<>(decode(r.getResultJson(), type), r.getEtag(), r.getLocation(), r.getStatus());
    }

    private void persist(long actor, String op, String target, String key, Object command, String eventId, String aggregate, long version, String operation, Object before, Object after, String reason, String reference, String source, String corrects, Result<?> result, LocalDateTime now) {
        persist(actor, op, target, key, command, eventId, aggregate, version, operation, before, after, reason, reference, source, corrects, result, now, null);
    }

    private void persist(long actor, String op, String target, String key, Object command, String eventId, String aggregate, long version, String operation, Object before, Object after, String reason, String reference, String source, String corrects, Result<?> result, LocalDateTime now, LocalMembership localBefore) {
        String receipt = uuid();
        var e = new PersonRows.Event();
        e.setId(eventId);
        e.setAggregateType(aggregate);
        e.setAggregateId("PERSON".equals(aggregate) ? target : aggregateId(after));
        e.setAggregateVersion(version);
        e.setOperation(operation);
        e.setBeforeJson(before == null ? null : encode(before));
        e.setAfterJson(encode(after));
        e.setOperatorUserId(actor);
        e.setRecordedAt(now);
        e.setReason(reason);
        e.setVerificationReference(reference);
        e.setSource(source);
        e.setCorrectsEventId(corrects);
        e.setReceiptId(receipt);
        e.setTraceId(org.slf4j.MDC.get("traceId"));
        if (result.value() instanceof LocalMembership local) {
            e.setAggregateId(local.personId());
            e.setLocalOrganizationId(local.organizationId());
            e.setLocalBeforeJson(encode(localBefore));
            e.setLocalAfterJson(encode(new LocalAudit(((LocalMemberChange) command).kind().name(), local)));
        }
        if ("PERSON".equals(aggregate)) e.setPersonId(e.getAggregateId());
        one(mapper.insertEvent(e));
        var r = new PersonRows.Receipt();
        r.setId(receipt);
        r.setActor(actor);
        r.setOperation(op);
        r.setTarget(target);
        r.setCommandKey(key);
        r.setFingerprint(fingerprint(command));
        r.setResultJson(encode(result.value()));
        r.setEtag(result.etag());
        r.setLocation(result.location());
        r.setStatus(result.status());
        r.setEventId(eventId);
        r.setCreatedAt(now);
        one(mapper.insertReceipt(r));
        managementAudit.personFact(eventId, receipt, actor, op, aggregate, e.getAggregateId(), e.getPersonId(), e.getBeforeJson(), e.getAfterJson(), now);
    }

    private String aggregateId(Object after) {
        if (after instanceof RosterCompleteness r) return r.organizationId();
        if (after instanceof Organization o) return o.organizationId();
        if (after instanceof Scope s) return s.scopeGrantId();
        throw unavailable();
    }

    private String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw unavailable();
        }
    }

    private <T> T decode(String value, Class<T> type) {
        if (value == null) return null;
        try {
            return json.readValue(value, type);
        } catch (JsonProcessingException failure) {
            throw unavailable();
        }
    }

    private String fingerprint(Object value) {
        return hash(encode(value));
    }

    private <T> Result<T> result(T value, int status, String location) {
        return new Result<>(value, "\"" + fingerprint(value) + "\"", location, status);
    }

    private void etag(String supplied, Object value) {
        if (supplied == null) throw problem(HttpStatus.PRECONDITION_REQUIRED, "precondition-required");
        if (!supplied.matches("\"[^\"\\r\\n]+\""))
            throw ContractProblemException.validation(new ContractProblemException.Violation("header", "If-Match", "INVALID_VALUE", "Use a single strong ETag"));
        if (!result(value, 200, null).etag().equals(supplied))
            throw problem(HttpStatus.PRECONDITION_FAILED, "precondition-failed");
    }

    public static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String uuid() {
        return UUID.randomUUID().toString();
    }

    public static void one(int count) {
        if (count != 1) throw unavailable();
    }

    private void saveHead(PersonRows.Head head) {
        one(head.getRevision() == 1 ? mapper.insertHead(head) : mapper.saveHead(head));
    }

    private void self(long actor, String person) {
        if (managed.everBound(person, actor)) throw problem(HttpStatus.FORBIDDEN, "person-self-governance-forbidden");
    }

    public static void selfAccount(long actor, long user) {
        if (actor == user) throw problem(HttpStatus.FORBIDDEN, "person-self-governance-forbidden");
    }

    public static void future(LocalDateTime until, LocalDateTime now) {
        if (until != null && (!until.isAfter(now) || until.getNano() % 1000 != 0)) throw invalid("validUntil");
    }

    public static void key(String key) {
        if (key == null || !key.matches("[A-Za-z0-9_-]{1,128}"))
            throw ContractProblemException.validation(new ContractProblemException.Violation("header", "Idempotency-Key", "INVALID_VALUE", "Invalid idempotency key"));
    }

    public static void page(int page, int size) {
        if (page < 1 || size < 1 || size > 100) throw invalid("page");
    }

    public static long userId(String value) {
        try {
            if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw missing();
            return Long.parseLong(value);
        } catch (NumberFormatException failure) {
            throw missing();
        }
    }

    public static String targetId(Target target) {
        return "PERSON".equals(target.type()) ? target.personId() : target.organizationId();
    }

    public static String actionKey(ActionSet set) {
        return set.actions().stream().map(Enum::name).sorted().reduce((a, b) -> a + "," + b).orElseThrow();
    }

    public static void text(String value, int max, String field, boolean empty) {
        if (value == null || value.codePointCount(0, value.length()) > max || !empty && value.replaceAll("[\\s\\p{Z}\\uFEFF]", "").isEmpty())
            throw invalid(field);
    }

    public static void reason(String reason, String reference) {
        text(reason, 2000, "reason", false);
        text(reference, 256, "verificationReference", false);
    }

    public static void validate(IdentityChange c) {
        if (c == null || c.kind() == null) throw invalid("kind");
        reason(c.reason(), c.verificationReference());
        boolean stage = Set.of(ChangeKind.RECORD, ChangeKind.TRANSITION, ChangeKind.CORRECT).contains(c.kind());
        if (stage && c.stageId() == null || !stage && (c.stageId() != null || c.validUntil() != null) || (c.kind() == ChangeKind.CORRECT) != (c.correctsChangeId() != null))
            throw invalid("kind");
    }

    public static void validate(MembershipChange c) {
        if (c == null || c.verification() == null) throw invalid("verification");
        reason(c.reason(), c.verificationReference());
        if ((c.verification() == Verification.VERIFIED) != (c.memberships() != null)) throw invalid("memberships");
        if (c.memberships() != null && (c.memberships().size() > 100 || c.memberships().stream().map(MemberInput::organizationId).distinct().count() != c.memberships().size()))
            throw invalid("memberships");
    }

    public static void validate(OrganizationChange c, boolean create) {
        reason(c.reason(), c.verificationReference());
        if (c.name() != null || create) text(c.name(), 120, "name", false);
        if (c.description() != null || create) text(c.description(), 2000, "description", true);
        if (c.type() != null && !Set.of("PARTY_COMMITTEE", "PARTY_GENERAL_BRANCH", "PARTY_BRANCH", "GENERAL_UNIT", "UNCLASSIFIED").contains(c.type()) || create && c.type() == null)
            throw invalid("type");
        if (create && c.status() != null || !create && c.name() == null && c.description() == null && c.status() == null && c.type() == null || c.status() != null && !Set.of("ACTIVE", "INACTIVE").contains(c.status()))
            throw invalid("status");
    }

    public static void validate(ScopeChange c) {
        if (c == null || c.target() == null || c.actionSet() == null || c.actionSet().domain() == null)
            throw invalid("target");
        reason(c.reason(), c.verificationReference());
        userId(c.granteeUserId());
        if (!Set.of("PERSON", "ORGANIZATION").contains(c.target().type()) || targetId(c.target()) == null)
            throw invalid("target");
        if ("PERSON".equals(c.target().type())) text(c.target().personId(), 128, "personId", false);
        if (c.actionSet().actions().isEmpty() || c.actionSet().actions().stream().distinct().count() != c.actionSet().actions().size() || c.actionSet().actions().stream().anyMatch(a -> !PersonFactReaders.supported(c.actionSet().domain(), a)))
            throw invalid("actions");
    }

    private static PersonRows.Head mutable(String person, PersonRows.Head h) {
        if (h != null) return h;
        h = new PersonRows.Head();
        h.setPersonId(person);
        h.setIdentityVerification("UNKNOWN");
        h.setMembershipVerification("UNKNOWN");
        return h;
    }

    private static void clearIdentity(PersonRows.Head h) {
        h.setIdentityPresence(null);
        h.setCurrentRecordId(null);
        h.setStageId(null);
        h.setIdentityStatus(null);
        h.setIdentityValidFrom(null);
        h.setIdentityValidUntil(null);
        h.setIdentityVerifiedBy(null);
        h.setIdentityVerifiedAt(null);
    }
}
