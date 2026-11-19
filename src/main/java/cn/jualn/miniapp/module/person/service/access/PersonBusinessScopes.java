package cn.jualn.miniapp.module.person.service.access;

import cn.jualn.miniapp.module.person.entity.ManagedPersonRows;
import cn.jualn.miniapp.module.person.entity.PersonRows;
import cn.jualn.miniapp.module.person.mapper.ManagedPersonMapper;
import cn.jualn.miniapp.module.person.service.binding.PersonBindingTrust;
import cn.jualn.miniapp.module.person.service.facts.PersonProjection;
import cn.jualn.miniapp.module.person.service.facts.PersonCatalog;
import cn.jualn.miniapp.module.person.bo.PersonBO.Verification;
import cn.jualn.miniapp.module.person.mapper.PersonBusinessMapper;
import cn.jualn.miniapp.module.person.mapper.PersonMapper;
import cn.jualn.miniapp.module.person.service.support.PersonnelValues;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

import static cn.jualn.miniapp.module.person.PersonProblems.unavailable;
import static cn.jualn.miniapp.module.person.PersonProblems.missing;
import static cn.jualn.miniapp.module.person.PersonProblems.conflict;

/**
 * Person-owned targets and cycle evidence. No authorization/session cache and no role inference.
 * Caller owns the common gate -> RBAC mutex -> ordered subjects transaction for writes.
 */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class PersonBusinessScopes {
    public record Scope(String id, long granteeUserId, String dutyCode, String assignmentId, String authorityId,
                        String rootAssignmentId,
                        String selection, String memberBindings, long grantedBy, LocalDateTime grantedAt,
                        LocalDateTime expiresAt, String changeId,
                        LocalDateTime revokedAt, Long revokedBy, String revocationReason) {
    }

    public record Roster(String verification, String version, String membershipDigest, String sourceReference) {
    }

    public record MemberRef(String personId, String membershipId) {
    }

    private final PersonBusinessMapper mapper;
    private final PersonMapper facts;
    private final ManagedPersonMapper managed;
    private final PersonCatalog catalog;

    private static void requireTrustedBinding(ManagedPersonRows.Binding binding) {
        // Public projections use generic denial; each business owner retains its own protocol.
        if (!PersonBindingTrust.trusted(binding)) throw cn.jualn.miniapp.module.person.PersonProblems.denied();
    }

    public long lock(boolean write) {
        return write ? facts.gateExclusive() : facts.gateShared();
    }

    /**
     * Person-owned database time in the caller's existing transaction.
     */
    public LocalDateTime now() {
        return facts.now();
    }

    public record DeliveryBinding(String status, Long userId, String bindingId) {
    }

    /**
     * Current Person lifecycle and binding; caller must already hold the common Person gate.
     */
    public DeliveryBinding deliveryBinding(String personId) {
        var profile = managed.profile(personId);
        if (profile == null) return null;
        var binding = managed.personBinding(personId);
        if (binding != null && !PersonBindingTrust.trusted(binding))
            return new DeliveryBinding("VERIFICATION_REQUIRED", null, null);
        return new DeliveryBinding(profile.getStatus(), binding == null ? null : binding.getUserId(),
                binding == null ? null : binding.getId());
    }

    public String trustedBoundPerson(long userId) {
        var binding = managed.accountBinding(userId);
        return PersonBindingTrust.trusted(binding) ? binding.getPersonId() : null;
    }

    public String boundPerson(long userId) {
        var binding = managed.accountBinding(userId);
        if (binding != null) requireTrustedBinding(binding);
        return binding == null ? null : binding.getPersonId();
    }

    public record AccountBinding(String personId, String bindingId) {
    }

    public record Lifecycle(String status) {
    }

    public AccountBinding currentAccountBinding(long userId) {
        var binding = managed.accountBinding(userId);
        if (binding != null) requireTrustedBinding(binding);
        return binding == null ? null : new AccountBinding(binding.getPersonId(), binding.getId());
    }

    public Lifecycle lifecycle(String personId) {
        var profile = managed.profile(personId);
        return profile == null ? null : new Lifecycle(profile.getStatus());
    }

    public boolean historicallyBound(String personId, long userId) {
        return managed.everBound(personId, userId);
    }

    public long epoch() {
        return facts.currentEpoch();
    }

    public void advance(long expected) {
        if (facts.advanceEpoch(expected) != 1) throw unavailable();
    }

    public List<String> organizations() {
        return mapper.activeOrganizations();
    }

    public List<String> allOrganizations() {
        return mapper.allOrganizations();
    }

    public void requireRoster(String organization, LocalDateTime at) {
        requireOrganization(organization);
        var roster = mapper.completeness(organization);

        // 完整性检查
        requireCompletenessEvidence(roster);

        // Exhaustive consumers require evidence for these exact current membership cycles.
        if (roster == null
                || !"COMPLETE".equals(roster.getStatus())
                || !membershipVersion(organization, at)
                .equals(roster.getConfirmedMembershipVersion()))
            throw unavailable();

        registeredRoster(organization, at);
    }

    public List<MemberRef> registeredRoster(String organization, LocalDateTime at) {
        requireOrganization(organization);
        var members = mapper.currentMembers(organization, at);
        for (var member : members)
            if (!member.membershipId().equals(cycle(member.personId(), organization, at))) throw unavailable();
        return members;
    }

    public void requireCompletenessEvidence(PersonRows.RosterCompleteness row) {
        if (row == null || row.getRevision() == 0 && "UNCONFIRMED".equals(row.getStatus())) return;
        if (!hasCompletenessEvidence(row)) throw unavailable();
    }

    public boolean hasCompletenessEvidence(PersonRows.RosterCompleteness row) {
        if (row == null || row.getLastChangeId() == null || row.getConfirmedMembershipVersion() == null
                || row.getSourceReference() == null || row.getConfirmedAt() == null || row.getConfirmedBy() == null)
            return false;
        var event = facts.event(row.getLastChangeId());
        if (event == null || !"ROSTER_COMPLETE".equals(event.getAggregateType())
                || !row.getOrganizationId().equals(event.getAggregateId()) || event.getAggregateVersion() != row.getRevision())
            return false;
        final com.fasterxml.jackson.databind.JsonNode value;
        try {
            value = PersonnelValues.tree(event.getAfterJson());
        } catch (cn.jualn.miniapp.common.exception.ContractProblemException failure) {
            return false;
        }
        return row.getStatus().equals(value.path("status").asText())
                && row.getConfirmedMembershipVersion().equals(value.path("confirmedMembershipVersion").asText())
                && Long.toString(row.getConfirmedBy()).equals(value.path("confirmedByUserId").asText())
                && row.getSourceReference().equals(value.path("verificationReference").asText())
                && businessHistoryCount(row) == row.getRevision();
    }

    private long businessHistoryCount(PersonRows.RosterCompleteness row) {
        return mapper.rosterHistoryCount(row.getOrganizationId());
    }

    public String membershipVersion(String organization, LocalDateTime at) {
        String evidence = organization + ":" + mapper.rosterDependencies(organization, at).toString();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(evidence.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public List<String> rosterMembers(String organization, LocalDateTime at) {
        requireRoster(organization, at);
        return mapper.currentMembers(organization, at).stream().map(MemberRef::personId).distinct().sorted().toList();
    }

    public void requireDevelopmentStages(List<String> stages) {
        if (stages.stream().anyMatch(id -> !catalog.confirmed(id))) throw unavailable();
    }

    public String developmentStage(String person, LocalDateTime at) {
        if (facts.corruptSource() != 0 || facts.corruptFacts() != 0 || facts.corruptPerson(person) != 0)
            throw unavailable();
        var h = facts.head(person);
        if (h == null || !"VERIFIED".equals(h.getIdentityVerification())) throw unavailable();
        if (!"RECORDED".equals(h.getIdentityPresence())) return null;
        if (!"ACTIVE".equals(h.getIdentityStatus()) || h.getIdentityValidFrom().isAfter(at) || (h.getIdentityValidUntil() != null && !h.getIdentityValidUntil().isAfter(at)))
            return null;
        return h.getStageId();
    }

    public record OrganizationFact(String organizationId, String name, String status) {
    }

    public OrganizationFact organizationFact(String id) {
        var org = facts.organization(id);
        return org == null ? null : new OrganizationFact(org.getId(), org.getName(), org.getStatus());
    }

    public boolean isActivePartyOrganization(String id) {
        if (id == null) return false;
        var org = facts.organization(id);
        return org != null && cn.jualn.miniapp.module.person.service.roster.RosterAccess.activePartyType(org.getStatus(), org.getType());
    }

    public String organizationName(String id) {
        requireOrganization(id);
        return facts.organization(id).getName();
    }

    /**
     * Positive local evidence only. This list never claims completeness.
     */
    public List<String> positiveOrganizations(String person, LocalDateTime at) {
        if (facts.corruptSource() != 0 || facts.corruptFacts() != 0 || facts.corruptPerson(person) != 0)
            throw unavailable();
        var rows = facts.memberships(person);
        var head = facts.head(person);
        var result = new java.util.TreeSet<String>();
        for (var row : rows) {
            var org = facts.organization(row.getOrganizationId());
            if (org == null) throw unavailable();
            if (!"ACTIVE".equals(org.getStatus())) continue;
            var local = PersonProjection.localMembership(person, org.getId(), head, facts.localMembership(person, org.getId()), rows, at);
            if (local.verification() == Verification.VERIFIED && "PRESENT".equals(local.presence()))
                result.add(org.getId());
        }
        return List.copyOf(result);
    }

    public List<String> completeMemberships(String person, LocalDateTime now) {
        if (facts.corruptSource() != 0 || facts.corruptFacts() != 0 || facts.corruptPerson(person) != 0)
            throw unavailable();
        var h = facts.head(person);
        if (h == null || !"VERIFIED".equals(h.getMembershipVerification())) throw unavailable();
        var rows = facts.memberships(person);
        for (var m : rows) requireOrganization(m.getOrganizationId());
        return PersonProjection.memberships(person, h, rows, now).currentMemberships().stream().map(m -> m.organizationId()).toList();
    }

    public void requireOrganization(String id) {
        var org = facts.organization(id);
        if (org == null) throw missing();
        if (!"ACTIVE".equals(org.getStatus())) throw conflict();
    }

    public String cycle(String person, String organization, LocalDateTime now) {
        requireOrganization(organization);
        if (facts.corruptSource() != 0 || facts.corruptFacts() != 0 || facts.corruptPerson(person) != 0)
            throw unavailable();
        var fact = PersonProjection.localMembership(person, organization, facts.head(person), facts.localMembership(person, organization), facts.memberships(person), now);
        if (fact.verification() != Verification.VERIFIED) throw unavailable();
        return "PRESENT".equals(fact.presence()) ? fact.membershipId() : null;
    }

    public boolean sameCycle(String person, String organization, String membership, LocalDateTime at) {
        var org = facts.organization(organization);
        if (org == null || !"ACTIVE".equals(org.getStatus())) return false;
        return membership != null && membership.equals(cycle(person, organization, at));
    }

    /**
     * Future membership boundaries in the same primary snapshot as the duty proof.
     */
    public LocalDateTime nextMembershipChange(String person, LocalDateTime at, LocalDateTime limit) {
        var result = limit;
        for (var membership : facts.memberships(person)) {
            for (var boundary : new LocalDateTime[]{membership.getValidFrom(), membership.getValidUntil()}) {
                if (boundary != null && boundary.isAfter(at) && boundary.isBefore(result)) {
                    result = boundary;
                }
            }
        }
        return result;
    }

    public void verifyDirectoryFacts() {
        if (facts.corruptSource() != 0 || facts.corruptFacts() != 0) throw unavailable();
    }

    public List<String> people() {
        return managed.people();
    }

    public void requirePerson(String person) {
        var p = managed.profile(person);
        if (p == null) throw missing();
        if (!"ACTIVE".equals(p.getStatus())) throw conflict();
    }

    public record MemberBinding(String personId, String bindingId, String membershipId) {
    }

    public MemberBinding memberBinding(long user, String org, LocalDateTime at) {
        var binding = managed.accountBinding(user);
        if (binding == null || !PersonBindingTrust.trusted(binding)) return null;
        requirePerson(binding.getPersonId());
        String membership = cycle(binding.getPersonId(), org, at);
        return membership == null ? null : new MemberBinding(binding.getPersonId(), binding.getId(), membership);
    }

    public boolean sameBindingCycle(long user, String person, String binding, String org, String membership, LocalDateTime at) {
        if (person == null || binding == null || membership == null) return false;
        var current = managed.accountBinding(user);
        if (current == null || !binding.equals(current.getId()) || !person.equals(current.getPersonId())) return false;
        if (!PersonBindingTrust.trusted(current)) return false;
        var profile = managed.profile(person);
        return profile != null && "ACTIVE".equals(profile.getStatus()) && sameCycle(person, org, membership, at);
    }

    public long accountForPerson(String person) {
        var profile = managed.profile(person);
        if (profile == null || !"ACTIVE".equals(profile.getStatus())) throw conflict();
        var binding = managed.personBinding(person);
        if (binding == null) throw conflict();
        requireTrustedBinding(binding);
        return binding.getUserId();
    }

    public String currentPerson(long user) {
        var b = managed.accountBinding(user);
        if (b == null) throw conflict();
        requireTrustedBinding(b);
        var p = managed.profile(b.getPersonId());
        if (p == null) throw unavailable();
        if (!"ACTIVE".equals(p.getStatus())) throw conflict();
        return b.getPersonId();
    }

    public String developmentStage(long user, LocalDateTime at) {
        return developmentStage(currentPerson(user), at);
    }

    public List<String> positiveOrganizations(long user, LocalDateTime at) {
        return positiveOrganizations(currentPerson(user), at);
    }

    public List<String> completeMemberships(long user, LocalDateTime at) {
        return completeMemberships(currentPerson(user), at);
    }

    public String cycle(long user, String org, LocalDateTime at) {
        return cycle(currentPerson(user), org, at);
    }

    public boolean sameCycle(long user, String org, String membership, LocalDateTime at) {
        var b = managed.accountBinding(user);
        return b != null && sameCycle(b.getPersonId(), org, membership, at);
    }

    public LocalDateTime nextMembershipChange(long user, LocalDateTime at, LocalDateTime limit) {
        var b = managed.accountBinding(user);
        return b == null ? limit : nextMembershipChange(b.getPersonId(), at, limit);
    }

    public Scope scope(String id) {
        return mapper.scope(id);
    }

    public Scope assignmentScope(String id) {
        return mapper.assignmentScope(id);
    }

    public List<Scope> forUser(long user) {
        return mapper.forUser(user);
    }

    public List<Scope> forAuthority(String authority) {
        return mapper.forAuthority(authority);
    }

    public void grant(Scope scope) {
        if (mapper.insertScope(scope) != 1) throw unavailable();
    }

    public void revoke(String id, long actor, String reason, LocalDateTime now) {
        if (mapper.revokeScope(id, actor, reason, now) != 1) throw conflict();
    }
}
