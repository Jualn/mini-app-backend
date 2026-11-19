package cn.jualn.miniapp.module.person.service.facts;

import cn.jualn.miniapp.module.person.entity.PersonRows;
import cn.jualn.miniapp.module.person.bo.PersonBO.Action;
import cn.jualn.miniapp.module.person.bo.PersonBO.ActionSet;
import cn.jualn.miniapp.module.person.bo.PersonBO.Domain;
import cn.jualn.miniapp.module.person.bo.PersonBO.Identity;
import cn.jualn.miniapp.module.person.bo.PersonBO.IdentityRecord;
import cn.jualn.miniapp.module.person.bo.PersonBO.LocalMembership;
import cn.jualn.miniapp.module.person.bo.PersonBO.Member;
import cn.jualn.miniapp.module.person.bo.PersonBO.Memberships;
import cn.jualn.miniapp.module.person.bo.PersonBO.Organization;
import cn.jualn.miniapp.module.person.bo.PersonBO.Scope;
import cn.jualn.miniapp.module.person.bo.PersonBO.Target;
import cn.jualn.miniapp.module.person.bo.PersonBO.Verification;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * Owner's immutable current projections. Unknown is never represented as NONE or [].
 */
public final class PersonProjection {
    private PersonProjection() {
    }

    public static LocalMembership localMembership(String person, String organization, PersonRows.Head h, PersonRows.LocalMembership local, List<PersonRows.Membership> rows, LocalDateTime at) {
        String version = h == null ? "0" : Long.toString(h.getRevision());
        var member = rows.stream().filter(m -> organization.equals(m.getOrganizationId()) && !m.getValidFrom().isAfter(at)
                && (m.getValidUntil() == null || m.getValidUntil().isAfter(at))).findFirst().orElse(null);
        boolean complete = h != null && "VERIFIED".equals(h.getMembershipVerification());
        if (local == null && !complete)
            return new LocalMembership(person, organization, h != null ? Verification.valueOf(h.getMembershipVerification()) : Verification.UNKNOWN, null, null, null, version, null, null);
        Long verifier = local != null ? local.getVerifiedBy() : h.getMembershipVerifiedBy();
        var verifiedAt = local != null ? local.getVerifiedAt() : h.getMembershipVerifiedAt();
        return new LocalMembership(person, organization, Verification.VERIFIED, member == null ? "ABSENT" : "PRESENT",
                member == null ? null : member.getId(), member == null ? null : member.getValidFrom(), version, Long.toString(verifier), verifiedAt);
    }

    public static Identity identity(String person, PersonRows.Head h) {
        if (h == null) return new Identity(person, Verification.UNKNOWN, null, null, "0", null, null, null);
        boolean known = "VERIFIED".equals(h.getIdentityVerification());
        var record = known && "RECORDED".equals(h.getIdentityPresence()) ? new IdentityRecord(h.getCurrentRecordId(), "PARTY_DEVELOPMENT", h.getStageId(), h.getIdentityStatus(), h.getIdentityValidFrom(), h.getIdentityValidUntil()) : null;
        return new Identity(person, Verification.valueOf(h.getIdentityVerification()), known ? h.getIdentityPresence() : null, record, Long.toString(h.getRevision()), h.getLastChangeId(), known ? Long.toString(h.getIdentityVerifiedBy()) : null, known ? h.getIdentityVerifiedAt() : null);
    }

    public static Memberships memberships(String person, PersonRows.Head h, List<PersonRows.Membership> rows, LocalDateTime at) {
        if (h == null) return new Memberships(person, Verification.UNKNOWN, null, "0", null, null, null, List.of(), "REGISTERED_CURRENT_MEMBERS");
        boolean known = "VERIFIED".equals(h.getMembershipVerification());
        var current = rows.stream().filter(m -> !m.getValidFrom().isAfter(at) && (m.getValidUntil() == null || m.getValidUntil().isAfter(at)))
                .sorted(java.util.Comparator.comparing(PersonRows.Membership::getOrganizationId))
                .map(m -> new Member(m.getId(), m.getOrganizationId(), m.getValidFrom(), m.getValidUntil())).toList();
        var members = known ? current : null;
        return new Memberships(person, Verification.valueOf(h.getMembershipVerification()), members, Long.toString(h.getRevision()), h.getLastChangeId(), known ? Long.toString(h.getMembershipVerifiedBy()) : null, known ? h.getMembershipVerifiedAt() : null,
                current, "REGISTERED_CURRENT_MEMBERS");
    }

    public static Organization organization(PersonRows.Organization r) {
        return new Organization(r.getId(), r.getName(), r.getDescription(), r.getStatus(), Long.toString(r.getRevision()), r.getCreatedAt(), r.getUpdatedAt(), r.getType());
    }

    public static Scope scope(PersonRows.Scope r, LocalDateTime at) {
        String status = r.getRevokedAt() != null ? "REVOKED" : r.getValidUntil() != null && !r.getValidUntil().isAfter(at) ? "EXPIRED" : "ACTIVE";
        return new Scope(r.getId(), Long.toString(r.getGranteeUserId()), r.getMode(), target(r), new ActionSet(Domain.valueOf(r.getDomain()), Arrays.stream(r.getActions().split(",")).map(Action::valueOf).toList()), status,
                r.getValidFrom(), r.getValidUntil(), Long.toString(r.getGrantedBy()), r.getReason(), r.getVerificationReference(), r.getSource(), r.getAuthorityGrantId(), Long.toString(r.getRevision()), r.getRevokedAt(), r.getRevokedBy() == null ? null : Long.toString(r.getRevokedBy()), r.getRevokeReason());
    }

    public static Target target(PersonRows.Scope r) {
        return new Target(r.getTargetType(), "PERSON".equals(r.getTargetType()) ? r.getTargetId() : null, "ORGANIZATION".equals(r.getTargetType()) ? r.getTargetId() : null);
    }
}
