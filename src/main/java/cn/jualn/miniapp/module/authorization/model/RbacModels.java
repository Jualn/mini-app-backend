package cn.jualn.miniapp.module.authorization.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Internal DB projections and immutable decisions. These are not HTTP representations.
 */
public final class RbacModels {
    private RbacModels() {
    }

    public enum Source {LEGACY, ASSIGNMENT}

    public record AuthorityCoverage(long users, long missing, long legacy, long assignment, long corrupt) {
    }

    public record InitialControlOrigin(long ordinaryGrants, long offlineGrants) {
    }

    @Data
    public static class Subject {
        private Long userId;
        private Integer status;
        private Integer legacyRole;
        private LocalDateTime banExpireAt;
        private LocalDateTime deletedAt;
        private String source;
        private String version;
    }

    @Data
    public static class Definition {
        private String code;
        private String namespaces;
        private String lifecycle;
        private String readiness;
        private boolean grantable;
        private String unavailableReason;
        private String catalogDigest;
    }

    public record Member(String roleCode, String permissionCode) {
    }

    public record OfflineGrantProof(String requestedId, String requestedGrantId, String effectId, Long userId,
                                    String assignmentId, String beforeSnapshot,
                                    String afterSnapshot, String operationId, String kind, Integer snapshotVersion,
                                    String receipt, LocalDateTime occurredAt) {
    }

    @Data
    public static class Assignment {
        @JsonIgnore
        private String originKind = "STANDALONE";
        private String id;
        private Long userId;
        private String roleCode;
        private String namespace;
        private String scopeType;
        private LocalDateTime grantedAt;
        private LocalDateTime expiresAt;
        private LocalDateTime revokedAt;
        private String version;
        private String grantChangeId;
        private String revokeChangeId;

        public String status(LocalDateTime now) {
            return revokedAt != null ? "REVOKED" : expiresAt != null && !expiresAt.isAfter(now) ? "EXPIRED" : "ACTIVE";
        }
    }

    public record Grant(String assignmentId, String roleCode, String namespace, String scopeType,
                        List<String> permissionCodes, LocalDateTime expiresAt, String dutyCode,
                        UnifiedModels.Target businessTarget) {
        public Grant(String assignmentId, String roleCode, String namespace, String scopeType, List<String> permissionCodes, LocalDateTime expiresAt) {
            this(assignmentId, roleCode, namespace, scopeType, permissionCodes, expiresAt, null, null);
        }

        public Grant {
            permissionCodes = List.copyOf(permissionCodes);
        }

        public boolean allows(String permission, String space) {
            return "GLOBAL".equals(scopeType) && namespace.equals(space) && permissionCodes.contains(permission)
                && (businessTarget==null || !"ORGANIZATION".equals(businessTarget.type()) || !java.util.Set.of("POST_MODERATE","POST_SAFETY_REVIEW","REPORT_HANDLE").contains(dutyCode));
        }
    }

    public record OwnerCapability(String rootAssignmentId, String namespace, String scopeType,
                                  List<String> permissionCodes) {
        public OwnerCapability {
            permissionCodes = List.copyOf(permissionCodes);
        }
    }

    public record Decision(long userId, Source source, String authorizationVersion, String catalogVersion,
                           LocalDateTime evaluatedAt,
                           boolean accountEligible, List<String> legacyPermissions, List<Grant> grants,
                           List<OwnerCapability> ownerCapabilities,
                           @JsonIgnore boolean delegationEligible) {
        public Decision {
            legacyPermissions = List.copyOf(legacyPermissions);
            grants = List.copyOf(grants);
            ownerCapabilities = List.copyOf(ownerCapabilities);
        }

        public Decision(long userId, Source source, String authorizationVersion, String catalogVersion, LocalDateTime evaluatedAt,
                        boolean accountEligible, List<String> legacyPermissions, List<Grant> grants) {
            this(userId, source, authorizationVersion, catalogVersion, evaluatedAt, accountEligible, legacyPermissions, grants, List.of());
        }

        public Decision(long userId, Source source, String authorizationVersion, String catalogVersion, LocalDateTime evaluatedAt,
                        boolean accountEligible, List<String> legacyPermissions, List<Grant> grants, List<OwnerCapability> ownerCapabilities) {
            this(userId, source, authorizationVersion, catalogVersion, evaluatedAt, accountEligible, legacyPermissions, grants, ownerCapabilities, false);
        }

        public boolean owns(String assignmentId) {
            return accountEligible && source == Source.ASSIGNMENT && grants.stream().anyMatch(g ->
                    g.assignmentId().equals(assignmentId) && "ROOT".equals(g.roleCode()) && g.expiresAt() == null
                            && g.allows("authorization:root-manage", "AUTHORIZATION"));
        }

        /**
         * Only ASSIGNMENT witnesses can authorize a new scoped operation. LEGACY is separate.
         */
        public boolean allows(String permission, String namespace) {
            return accountEligible && source == Source.ASSIGNMENT && (grants.stream().anyMatch(g -> g.allows(permission, namespace))
                    || ownerCapabilities.stream().anyMatch(c -> owns(c.rootAssignmentId()) && "GLOBAL".equals(c.scopeType())
                    && c.namespace().equals(namespace) && c.permissionCodes().contains(permission)));
        }

        public String managementOwner(String namespace, String... permissions) {
            if (!accountEligible || source != Source.ASSIGNMENT || permissions.length == 0) return null;
            return ownerCapabilities.stream().filter(c -> owns(c.rootAssignmentId()) && "GLOBAL".equals(c.scopeType())
                    && namespace.equals(c.namespace()) && c.permissionCodes().containsAll(java.util.Arrays.asList(permissions)))
                    .map(OwnerCapability::rootAssignmentId).findFirst().orElse(null);
        }

        public boolean allowsWork(String duty, String namespace, String... permissions) {
            return accountEligible && source == Source.ASSIGNMENT && permissions.length > 0 && (managementOwner(namespace, permissions) != null || grants.stream().anyMatch(g ->
                    duty.equals(g.dutyCode()) && g.businessTarget() != null && "PLATFORM".equals(g.businessTarget().type())
                            && java.util.Arrays.stream(permissions).allMatch(p -> g.allows(p, namespace))));
        }

        public boolean allowsAll(String namespace, String... permissions) {
            if (!accountEligible || source != Source.ASSIGNMENT || permissions.length == 0) return false;
            return grants.stream().anyMatch(g -> java.util.Arrays.stream(permissions).allMatch(p -> g.allows(p, namespace)))
                    || ownerCapabilities.stream().anyMatch(c -> owns(c.rootAssignmentId()) && "GLOBAL".equals(c.scopeType())
                    && namespace.equals(c.namespace()) && c.permissionCodes().containsAll(java.util.Arrays.asList(permissions)));
        }

        public boolean canUseAdmin() {
            return accountEligible && source == Source.ASSIGNMENT && (!grants.isEmpty() || delegationEligible);
        }
    }

    public record Command(long actorUserId, long targetUserId, String idempotencyKey, String reason,
                          String expectedAuthorizationVersion, String assignmentId, String expectedAssignmentVersion,
                          String roleCode, String namespace, String scopeType, LocalDateTime expiresAt) {
    }

    public record Receipt(String changeId, String operationId, String action, LocalDateTime committedAt,
                          String authorizationVersion, Assignment assignment) {
    }

    @Data
    public static class Change {
        private String id;
        private String operationId;
        private long actorUserId;
        private long targetUserId;
        private String assignmentId;
        private String action;
        private byte[] idempotencyKey;
        private String requestFingerprint;
        private String reason;
        private Integer snapshotVersion;
        private String beforeSnapshot;
        private String afterSnapshot;
        private String receipt;
        private LocalDateTime occurredAt;
    }
}
