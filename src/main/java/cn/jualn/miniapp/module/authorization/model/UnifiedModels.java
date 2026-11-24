package cn.jualn.miniapp.module.authorization.model;

import cn.jualn.miniapp.module.authorization.support.RbacProblems;
import cn.jualn.miniapp.module.authorization.support.RbacTime;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.ArrayList;
import java.util.HashSet;

import lombok.Data;

import static com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL;

/**
 * Canonical closed requests and safe owner projections. No role/permission writes are accepted from clients.
 */
public final class UnifiedModels {
    public record DutySummaryItem(String dutyCode, boolean effective) {
    }

    public record DutySummary(String userId, String authorizationVersion, String catalogVersion,
                              String evaluatedAt, String refreshAfter, List<DutySummaryItem> items) {
    }

    public record TargetOption(Target target, String label, String distinguishingLabel) {}
    public record TargetOptions(String evaluatedAt, String catalogVersion, String authorizationVersion,
                                List<TargetOption> items, String nextCursor) {}
    public record RecipientOrganization(String organizationId, String name, String distinguishingLabel) {}
    @JsonInclude(NON_NULL)
    public record RecipientPerson(String personId, String realName, List<RecipientOrganization> organizations) {}
    public record RecipientOption(String candidateId, String bindingState,
            @JsonInclude(JsonInclude.Include.ALWAYS) String userId,
            @JsonInclude(NON_NULL) String nickname, @JsonInclude(NON_NULL) RecipientPerson person,
            boolean selectable, String unavailableReason) {}
    public record RecipientOptions(String evaluatedAt, String catalogVersion, String authorizationVersion,
                                   List<RecipientOption> items, String nextCursor) {}

    private UnifiedModels() {
    }

    @JsonInclude(NON_NULL)
    public record Target(String type, String personId, String organizationId) {
    }

    @JsonInclude(NON_NULL)
    public record Selection(String dutyCode, Target target, String expiresAt, List<Target> audienceTargets) {
        public Selection {
            if (audienceTargets != null) audienceTargets = List.copyOf(audienceTargets);
        }
    }

    @JsonInclude(NON_NULL)
    public record Intent(String kind, String granteeUserId, List<Selection> items, String authorityId,
                         String sourceType, String sourceId, String reason) {
        public Intent {
            if (items != null) items = List.copyOf(items);
        }

        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public static Intent parse(JsonNode n) {
            String kind = text(n, "kind");
            if ("GRANT".equals(kind) || "REPLACE".equals(kind)) {
                if ("REPLACE".equals(kind)) closed(n, "kind", "granteeUserId", "items", "authorityId", "sourceId", "reason");
                else closed(n, "kind", "granteeUserId", "items", "authorityId", "reason");
                return new Intent(kind, text(n, "granteeUserId"), selections(n.get("items"), true), text(n, "authorityId"), null, "REPLACE".equals(kind) ? text(n, "sourceId") : null, optionalReason(n, "REPLACE".equals(kind) ? "调整业务权限" : "分配业务权限"));
            }
            if (!"REVOKE".equals(kind)) throw RbacProblems.invalid("kind");
            closed(n, "kind", "sourceType", "sourceId", "reason");
            String type = text(n, "sourceType");
            if (!Set.of("ASSIGNMENT", "SCOPE", "AUTHORIZATION", "DELEGATION_AUTHORITY").contains(type))
                throw RbacProblems.invalid("sourceType");
            return new Intent(kind, null, null, null, type, text(n, "sourceId"), optionalReason(n, "撤销业务授权"));
        }
    }

    public record Commit(String previewId, Intent intent) {
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public static Commit parse(JsonNode n) {
            closed(n, "previewId", "intent");
            return new Commit(text(n, "previewId"), Intent.parse(n.get("intent")));
        }
    }

    public record AuthorityRequest(String granteeUserId, List<Selection> ceilings, String recipientRule,
                                   String expiresAt, String reason) {
        public AuthorityRequest {
            ceilings = List.copyOf(ceilings);
        }

        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public static AuthorityRequest parse(JsonNode n) {
            closed(n, "granteeUserId", "ceilings", "recipientRule", "expiresAt", "reason");
            String rule = text(n, "recipientRule");
            if (!Set.of("SAME_ORGANIZATION_MEMBERS", "INDEPENDENT_APPROVAL").contains(rule))
                throw RbacProblems.invalid("recipientRule");
            return new AuthorityRequest(text(n, "granteeUserId"), selections(n.get("ceilings"), false), rule, time(n, "expiresAt"), UnifiedModels.reason(n));
        }
    }

    @JsonInclude(NON_NULL)
    public record Source(List<String> assignmentIds, List<String> scopeGrantIds, String delegationAuthorityId,
                         String membershipId, String grantedByUserId, String grantedAt, String changeId,
                         String personId, String bindingId) {
    }

    @JsonInclude(NON_NULL)
    public record EffectiveDuty(String dutyCode, Target target, boolean effective, String reason, Source source,
                                String expiresAt, List<Target> audienceTargets) {
    }

    @JsonInclude(NON_NULL)
    public record EffectiveDuties(String userId, String authorizationVersion, String catalogVersion, String evaluatedAt,
                                  List<EffectiveDuty> items, String nextCursor) {
    }

    @JsonInclude(NON_NULL)
    public record Option(String dutyCode, String roleCode, String namespace, List<String> permissionCodes,
                         List<Target> allowedTargets, String notAfter, String authorityId,
                         List<Target> audienceTargets) {
    }

    @JsonInclude(NON_NULL)
    public record Options(String evaluatedAt, String catalogVersion, boolean canViewAdvancedGovernance,
                          List<Option> items, String nextCursor) {
    }

    public record Impact(String userId, String dutyCode, Target target, String effect, boolean direct, Source source) {
    }

    @JsonInclude(NON_NULL)
    public record Preview(String previewId, String authorizationVersion, String evaluatedAt, String expiresAt,
                          boolean canCommit, String atomicity, List<Impact> impacts, String nextCursor) {
    }

    public record Receipt(String changeId, String authorizationVersion, String atomicity, String evaluatedAt,
                          List<String> assignmentIds, List<String> scopeGrantIds) {
    }

    @JsonInclude(NON_NULL)
    public record Authority(String authorityId, String granteeUserId, String grantedByUserId, String rootAssignmentId,
                            List<Selection> ceilings, String recipientRule, String membershipId, boolean canRedelegate,
                            String status, String expiresAt,
                            String version, String grantedAt, String reason, String revokedAt, String revokedByUserId,
                            String revocationReason, String personId, String bindingId) {
    }

    @JsonInclude(NON_NULL)
    public record AuthorityPage(List<Authority> items, String nextCursor) {
    }

    public record Binding(long userId, String personId, String bindingId, String organizationId, String membershipId) {
    }

    @Data
    public static class AuthorityRow {
        private String id;
        private long granteeUserId;
        private long grantedBy;
        private String rootAssignmentId;
        private String ceilings;
        private String memberBindings;
        private String recipientRule;
        private LocalDateTime grantedAt;
        private LocalDateTime expiresAt;
        private String reason;
        private long revision;
        private LocalDateTime revokedAt;
        private Long revokedBy;
        private String revocationReason;
        private String changeId;
    }

    @Data
    public static class PreviewRow {
        private String id;
        private long actor;
        private String intentJson;
        private String snapshotHash;
        private LocalDateTime createdAt;
        private LocalDateTime expiresAt;
    }

    @Data
    public static class CommandRow {
        private String id;
        private long actor;
        private String operation;
        private String target;
        private String commandKey;
        private String fingerprint;
        private String requestJson;
        private String resultJson;
        private String auditJson;
        private LocalDateTime occurredAt;
    }

    public record CursorRow(String id, long actor, String binding, long itemOffset, LocalDateTime expiresAt) {
    }

    static void closed(JsonNode n, String... allowed) {
        if (n == null || !n.isObject()) throw RbacProblems.invalid("body");
        Set<String> keys = Set.of(allowed);
        n.fieldNames().forEachRemaining(k -> {
            if (!keys.contains(k)) throw RbacProblems.invalid(k);
        });
    }

    static String optionalReason(JsonNode n, String fallback) {
        return n.has("reason") ? text(n, "reason") : fallback;
    }

    static String text(JsonNode n, String key) {
        if (n == null || !n.has(key) || !n.get(key).isTextual() || n.get(key).textValue().isBlank())
            throw RbacProblems.invalid(key);
        String value = n.get(key).textValue();
        if (value.codePointCount(0, value.length()) > 2000) throw RbacProblems.invalid(key);
        return value;
    }

    static String reason(JsonNode n) {
        return text(n, "reason");
    }

    static String time(JsonNode n, String key) {
        return RbacTime.wire(RbacTime.expiry(text(n, key)));
    }

    static Target target(JsonNode n) {
        String type = text(n, "type");
        return switch (type) {
            case "PERSON" -> {
                closed(n, "type", "personId");
                yield new Target(type, text(n, "personId"), null);
            }
            case "ORGANIZATION" -> {
                closed(n, "type", "organizationId");
                yield new Target(type, null, text(n, "organizationId"));
            }
            case "PLATFORM" -> {
                closed(n, "type");
                yield new Target(type, null, null);
            }
            default -> throw RbacProblems.invalid("target");
        };
    }

    static List<Selection> selections(JsonNode n, boolean optionalDeadline) {
        if (n == null || !n.isArray() || n.isEmpty() || n.size() > 20) throw RbacProblems.invalid("items");
        var result = new ArrayList<Selection>();
        for (var item : n) {
            closed(item, "dutyCode", "target", "expiresAt", "audienceTargets");
            List<Target> audience = null;
            if (item.has("audienceTargets")) {
                var nodes = item.get("audienceTargets");
                if (!nodes.isArray() || nodes.isEmpty() || nodes.size() > 100)
                    throw RbacProblems.invalid("audienceTargets");
                audience = new ArrayList<>();
                for (var node : nodes) audience.add(target(node));
                if (new HashSet<>(audience).size() != audience.size()) throw RbacProblems.invalid("audienceTargets");
            }
            String deadline = optionalDeadline && !item.has("expiresAt") ? null : time(item, "expiresAt");
            result.add(new Selection(text(item, "dutyCode"), target(item.get("target")), deadline, audience));
        }
        if (new HashSet<>(result).size() != result.size()) throw RbacProblems.invalid("items");
        return List.copyOf(result);
    }
}
