package cn.jualn.miniapp.module.authorization.service;

import cn.jualn.miniapp.module.authorization.mapper.RbacMapper;
import cn.jualn.miniapp.module.authorization.support.RbacProblems;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.*;

import static cn.jualn.miniapp.module.authorization.model.RbacModels.*;

/**
 * Checks known offline origins in the caller's primary-DB snapshot, never grants authority itself.
 */
final class RbacOfflineAudit {
    private static final ObjectMapper JSON = RbacJson.mapper();

    private RbacOfflineAudit() {
    }

    private record Reference(String id, String grantId) {
    }

    static void verify(RbacMapper mapper, Assignment assignment) {
        verify(mapper, List.of(assignment));
    }

    static void verify(RbacMapper mapper, List<Assignment> assignments) {
        // Local to this invocation/snapshot. Never reuse proofs across transactions or governance waits.
        Map<Reference, List<Assignment>> references = new LinkedHashMap<>();
        for (Assignment a : assignments)
            references.computeIfAbsent(new Reference(a.getId(), a.getGrantChangeId()), key -> new ArrayList<>()).add(a);
        List<Reference> keys = new ArrayList<>(references.keySet());
        for (int start = 0; start < keys.size(); start += 200) {
            var batch = keys.subList(start, Math.min(start + 200, keys.size()));
            List<Assignment> requested = batch.stream().map(key -> references.get(key).get(0)).toList();
            Map<Reference, List<OfflineGrantProof>> proofs = new HashMap<>();
            for (var proof : mapper.offlineGrantProofs(requested)) {
                var key = new Reference(proof.requestedId(), proof.requestedGrantId());
                if (!batch.contains(key)) throw RbacProblems.unavailable();
                proofs.computeIfAbsent(key, unused -> new ArrayList<>()).add(proof);
            }
            for (var key : batch)
                for (var assignment : references.get(key)) verify(assignment, proofs.getOrDefault(key, List.of()));
        }
    }

    private static void verify(Assignment assignment, List<OfflineGrantProof> proofs) {
        if (proofs.isEmpty()) return; // Ordinary grants keep their original Change owner/transaction semantics.
        if (proofs.size() != 1) throw RbacProblems.unavailable();
        var p = proofs.get(0);
        try {
            if (!Objects.equals(p.effectId(), assignment.getGrantChangeId()) || !Objects.equals(p.userId(), assignment.getUserId())
                    || !Objects.equals(p.assignmentId(), assignment.getId()) || p.operationId() == null || p.occurredAt() == null
                    || p.snapshotVersion() == null || !Set.of(1, 2, 3, 4).contains(p.snapshotVersion()) || p.kind() == null)
                throw RbacProblems.unavailable();
            JsonNode before = JSON.readTree(p.beforeSnapshot()), after = JSON.readTree(p.afterSnapshot()), receipt = JSON.readTree(p.receipt());
            if (before == null || after == null || receipt == null) throw RbacProblems.unavailable();
            boolean initialMigration = Integer.valueOf(2).equals(p.snapshotVersion());
            boolean correction = Integer.valueOf(3).equals(p.snapshotVersion());
            String role = correction && "INITIAL_ROLE_GRANT".equals(p.kind()) ? "LEARNING_MANAGER" : "ROOT";
            String namespace = "ROOT".equals(role) ? "AUTHORIZATION" : "LEARNING_TASK";
            boolean lifecycle = Integer.valueOf(4).equals(p.snapshotVersion());
            if (!correction && !(lifecycle ? Set.of("BOOTSTRAP", "TRANSFER", "RECOVERY") : Set.of("BOOTSTRAP", "RECOVERY")).contains(p.kind())
                    || correction && !Set.of("CORRECT_INITIAL_ROOT", "INITIAL_ROLE_GRANT").contains(p.kind()))
                throw RbacProblems.unavailable();
            if (correction && (!"ASSIGNMENT".equals(before.path("subject").path("source").asText())
                    || !receipt.path("effects").isArray()
                    || receipt.path("effects").size() != ("ROOT".equals(role) ? 2 : 1)))
                throw RbacProblems.unavailable();
            if (initialMigration && (!"BOOTSTRAP".equals(p.kind())
                    || !Set.of("LEGACY","ASSIGNMENT").contains(before.path("subject").path("source").asText())
                    || !before.path("assignments").isArray() || !before.path("assignments").isEmpty()
                    || !after.path("assignments").isArray() || after.path("assignments").size() != 1
                    || !receipt.path("effects").isArray() || receipt.path("effects").isEmpty()))
                throw RbacProblems.unavailable();
            if (initialMigration) {
                int rootEffects = 0;
                Set<Long> users = new HashSet<>();
                for (JsonNode effect : receipt.path("effects")) {
                    if (!users.add(effect.path("userId").asLong(-1))) throw RbacProblems.unavailable();
                    if (!effect.path("assignmentId").isNull() && !effect.path("assignmentId").isMissingNode())
                        rootEffects++;
                }
                if (rootEffects != 1) throw RbacProblems.unavailable();
            }
            if (before == null || after == null || receipt == null || receipt.path("version").asInt() != p.snapshotVersion()
                    || !p.operationId().equals(receipt.path("operationId").asText()) || !p.kind().equals(receipt.path("kind").asText())
                    || !p.occurredAt().equals(LocalDateTime.parse(receipt.path("committedAt").asText()))
                    || !receipt.path("effects").isArray() || (!initialMigration && !correction && !Set.of(1, 2).contains(receipt.path("effects").size()))
                    || after.path("subject").path("userId").asLong(-1) != assignment.getUserId()
                    || !"ASSIGNMENT".equals(after.path("subject").path("source").asText())
                    || !after.path("assignments").isArray()) throw RbacProblems.unavailable();
            int linked = 0;
            for (JsonNode effect : receipt.path("effects"))
                if (p.effectId().equals(effect.path("id").asText())) {
                    if (!p.assignmentId().equals(effect.path("assignmentId").asText()) || effect.path("userId").asLong(-1) != assignment.getUserId()
                            || !before.equals(effect.path("before")) || !after.equals(effect.path("after")))
                        throw RbacProblems.unavailable();
                    linked++;
                }
            if (linked != 1) throw RbacProblems.unavailable();
            int matches = 0;
            for (JsonNode node : after.path("assignments"))
                if (assignment.getId().equals(node.path("id").asText())) {
                    Assignment original = JSON.treeToValue(node, Assignment.class);
                    if (!Objects.equals(original.getUserId(), assignment.getUserId()) || !role.equals(original.getRoleCode())
                            || !original.getRoleCode().equals(assignment.getRoleCode()) || !namespace.equals(assignment.getNamespace())
                            || !Objects.equals(original.getNamespace(), assignment.getNamespace()) || !"GLOBAL".equals(assignment.getScopeType())
                            || !Objects.equals(original.getScopeType(), assignment.getScopeType()) || original.getExpiresAt() != null
                            || assignment.getExpiresAt() != null || original.getRevokedAt() != null || original.getRevokeChangeId() != null
                            || !Objects.equals(original.getGrantedAt(), assignment.getGrantedAt()) || !p.effectId().equals(original.getGrantChangeId()))
                        throw RbacProblems.unavailable();
                    matches++;
                }
            if (matches != 1) throw RbacProblems.unavailable();
        } catch (java.io.IOException | IllegalArgumentException e) {
            throw RbacProblems.unavailable();
        }
    }
}
