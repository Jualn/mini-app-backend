package cn.jualn.miniapp.module.person.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import cn.jualn.miniapp.module.person.bo.PersonBO.Verification;
import cn.jualn.miniapp.module.person.bo.PersonBO.Target;
import cn.jualn.miniapp.module.person.bo.PersonBO.ActionSet;

/** Canonical HTTP representations, IDs opaque strings, UTC RFC3339 with seconds. */
public final class PersonVO {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LocalMembership(String personId,String organizationId,Verification verification,String presence,String membershipId,
            String validFrom,String version,String verifiedByUserId,String verifiedAt,String realName,String personStatus) {}
    public record OrganizationMemberPage(String organizationId,String coverage,List<LocalMembership> items,int page,int pageSize,
            @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=PersonCountSerializer.class) long totalItems,String evaluatedAt) {}
    public record LocalHistory(String changeId,String personId,String organizationId,String operation,LocalMembership before,
            LocalMembership after,String operatorUserId,String recordedAt,String reason,String verificationReference,String source) {}
    private PersonVO() {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record IdentityRecord(String recordId,String identityKind,String stageId,String status,String validFrom,String validUntil) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Identity(String personId,Verification verification,String presence,IdentityRecord currentRecord,String version,String lastChangeId,String verifiedByUserId,String verifiedAt) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Member(String membershipId,String organizationId,String validFrom,String validUntil) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Memberships(String personId,Verification verification,List<Member> currentMemberships,String version,String lastChangeId,String verifiedByUserId,String verifiedAt,
                              List<Member> knownMemberships,String knownMembershipsCoverage) {}
    public record Organization(String organizationId,String name,String description,String status,String version,String createdAt,String updatedAt,String type) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Scope(String scopeGrantId,String granteeUserId,String mode,Target target,ActionSet actionSet,String status,String validFrom,String validUntil,String grantedByUserId,String reason,String verificationReference,String source,String authorityGrantId,String version,String revokedAt,String revokedByUserId,String revokeReason) {}
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record Summary(String personId,String realName,String status,Verification identityVerification,Verification membershipVerification,String disambiguation) {
        public Summary(String personId,String realName,String status,Verification identityVerification,Verification membershipVerification) {this(personId,realName,status,identityVerification,membershipVerification,null);}
    }
    public record History<T>(String changeId,String personId,String operation,T before,T after,String operatorUserId,String recordedAt,String reason,String verificationReference,String source) {}
    public record Page<T>(List<T> items,int page,int pageSize,@com.fasterxml.jackson.databind.annotation.JsonSerialize(using=PersonCountSerializer.class) long totalItems) {}
}
