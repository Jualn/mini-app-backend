package cn.jualn.miniapp.module.authorization.web;


import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.*;

/** Explicit canonical HTTP types; no persistence objects or internal snapshots are serialized. */
public final class RbacHttpModels {
    private RbacHttpModels() {}
    public record Scope(String type) {}
    public record Permission(String code,String description,List<String> namespaces,String lifecycle,String readiness,boolean grantable,String unavailableReason) {}
    public record Role(String code,String name,List<String> permissionCodes,List<String> namespaces,String lifecycle,String readiness,boolean grantable,String unavailableReason) {}
    public record PermissionCatalog(String catalogVersion,List<Permission> items) {}
    public record RoleCatalog(String catalogVersion,List<Role> items) {}
    public record Assignment(String assignmentId,String userId,String roleCode,String namespace,Scope scope,String grantedAt,
                             String expiresAt,String status,String assignmentVersion,String grantChangeId,String revokedAt,String revokeChangeId,String originKind) {}
    public record AssignmentPage(String userId,String authorizationVersion,String catalogVersion,String evaluatedAt,List<Assignment> items,
                                  @JsonInclude(JsonInclude.Include.NON_NULL) String nextCursor) {}
    public record AssignmentDetail(String userId,String authorizationVersion,String catalogVersion,String evaluatedAt,Assignment assignment) {}
    public record CapabilityGrant(String assignmentId,String roleCode,String namespace,Scope scope,List<String> permissionCodes,String expiresAt) {}
    public record OwnerCapability(String rootAssignmentId,String namespace,Scope scope,List<String> permissionCodes) {}
    public record Capabilities(String userId,String authorizationModel,boolean canUseAdmin,String authorizationVersion,String catalogVersion,
                               String evaluatedAt,String refreshAfter,List<CapabilityGrant> grants,List<OwnerCapability> ownerCapabilities) {}
    public record AuditEntry(String changeId,String operationId,String actorUserId,String targetUserId,String action,String assignmentId,
                             String occurredAt,String reason,String result,Assignment before,Assignment after) {}
    public record AuditPage(List<AuditEntry> items,@JsonInclude(JsonInclude.Include.NON_NULL) String nextCursor) {}
}
