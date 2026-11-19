package cn.jualn.miniapp.module.person.bo;

import java.time.LocalDateTime;
import java.util.List;

/** Owner projections, never persistence entities or client authorization claims. */
public final class PersonBO {
    private PersonBO() {}
    public record RosterChange(String action,String membershipVersion,String reason,String verificationReference) {}
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record RosterCompleteness(String organizationId,String status,String verification,String membershipVersion,String version,
        java.time.Instant evaluatedAt,String confirmedMembershipVersion,String confirmedByUserId,java.time.Instant confirmedAt,String verificationReference,String lastChangeId) {}
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record RosterEvent(String changeId,String organizationId,String action,java.time.Instant recordedAt,String membershipVersion,
        String operatorUserId,String reason,String verificationReference) {}

    public enum Verification { UNKNOWN, VERIFIED, DISPUTED }
    public enum Domain { IDENTITY, ORGANIZATION, POINTS }
    public enum Action { READ, MAINTAIN, REVIEW, REVERSE }
    public enum ChangeKind { RECORD, TRANSITION, INVALIDATE, REVOKE, CORRECT, VERIFY_NONE, MARK_UNKNOWN, MARK_DISPUTED }
    public enum LocalMemberKind { JOIN, REMOVE, VERIFY_PRESENT, VERIFY_ABSENT }
    public record LocalMemberChange(LocalMemberKind kind,String reason,String verificationReference) {}
    public record LocalMembership(String personId,String organizationId,Verification verification,String presence,
            String membershipId,LocalDateTime validFrom,String version,String verifiedByUserId,LocalDateTime verifiedAt,String realName,String personStatus) {
        public LocalMembership(String person,String organization,Verification verification,String presence,String membership,LocalDateTime from,String version,String actor,LocalDateTime at) {this(person,organization,verification,presence,membership,from,version,actor,at,null,null);}
    }
    public record OrganizationMemberPage(String organizationId,String coverage,List<LocalMembership> items,int page,int pageSize,long totalItems,LocalDateTime evaluatedAt) {
        public OrganizationMemberPage { items=List.copyOf(items); }
    }
    public record LocalHistory(String changeId,String personId,String organizationId,String operation,LocalMembership before,
            LocalMembership after,String operatorUserId,LocalDateTime recordedAt,String reason,String verificationReference,String source) {}
    public record IdentityChange(ChangeKind kind,String stageId,LocalDateTime validUntil,String correctsChangeId,String reason,String verificationReference) {}
    public record MemberInput(String organizationId,LocalDateTime validUntil) {}
    public record MembershipChange(Verification verification,List<MemberInput> memberships,String reason,String verificationReference) {
        public MembershipChange { if(memberships!=null) memberships=List.copyOf(memberships); }
    }
    public record OrganizationChange(String name,String description,String status,String reason,String verificationReference,String type) {}
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record Target(String type,String personId,String organizationId) {}
    public record ActionSet(Domain domain,List<Action> actions) { public ActionSet { actions=List.copyOf(actions); } }
    public record ScopeChange(String granteeUserId,Target target,ActionSet actionSet,LocalDateTime validUntil,String reason,String verificationReference) {}
    public record Reason(String reason,String verificationReference) {}
    public record IdentityRecord(String recordId,String identityKind,String stageId,String status,LocalDateTime validFrom,LocalDateTime validUntil) {}
    public record Identity(String personId,Verification verification,String presence,IdentityRecord currentRecord,String version,String lastChangeId,String verifiedByUserId,LocalDateTime verifiedAt) {}
    public record Member(String membershipId,String organizationId,LocalDateTime validFrom,LocalDateTime validUntil) {}
    public record Memberships(String personId,Verification verification,List<Member> currentMemberships,String version,String lastChangeId,String verifiedByUserId,LocalDateTime verifiedAt,
                              List<Member> knownMemberships,String knownMembershipsCoverage) {
        public Memberships {
            if(currentMemberships!=null) currentMemberships=List.copyOf(currentMemberships);
            if(knownMemberships!=null) knownMemberships=List.copyOf(knownMemberships);
        }
    }
    public record Organization(String organizationId,String name,String description,String status,String version,LocalDateTime createdAt,LocalDateTime updatedAt,String type) {}
    public record Scope(String scopeGrantId,String granteeUserId,String mode,Target target,ActionSet actionSet,String status,LocalDateTime validFrom,LocalDateTime validUntil,String grantedByUserId,String reason,String verificationReference,String source,String authorityGrantId,String version,LocalDateTime revokedAt,String revokedByUserId,String revokeReason) {}
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record Summary(String personId,String realName,String status,Verification identityVerification,Verification membershipVerification,String disambiguation) {
        @org.apache.ibatis.annotations.AutomapConstructor
        public Summary {}
        public Summary(String personId,String realName,String status,Verification identityVerification,Verification membershipVerification) {this(personId,realName,status,identityVerification,membershipVerification,null);}
    }
    public record OrganizationGroup(String organizationId,long totalPersons) {}
    public record History<T>(String changeId,String personId,String operation,T before,T after,String operatorUserId,LocalDateTime recordedAt,String reason,String verificationReference,String source) {}
    public record Page<T>(List<T> items,int page,int pageSize,long totalItems) { public Page { items=List.copyOf(items); } }
    public record Result<T>(T value,String etag,String location,int status) {}
}
