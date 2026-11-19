package cn.jualn.miniapp.module.authorization.service;

import cn.jualn.miniapp.module.admin.operation.service.AdminAuditJournal;
import cn.jualn.miniapp.module.authorization.mapper.RbacMapper;
import cn.jualn.miniapp.module.authorization.support.RbacProblems;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;

import cn.jualn.miniapp.module.authorization.model.RbacModels.*;

/**
 * Fixture for writing exact historical role assignments in isolated database tests.
 */
@Slf4j
class HistoricalRbacAssignmentWriter extends RbacGovernanceService {
    // JSON Schema uses ECMAScript \S, whose whitespace differs from Java isBlank/UNICODE_CHARACTER_CLASS.

    private final AdminAuditJournal managementAudit;

    private static final Pattern REASON_CONTENT = Pattern.compile(
            "[^\\x09-\\x0D\\x20\\x{00A0}\\x{1680}\\x{2000}-\\x{200A}\\x{2028}\\x{2029}\\x{202F}\\x{205F}\\x{3000}\\x{FEFF}]");
    private final RbacMapper mapper;
    private final RbacCatalog catalog;
    private final RbacAuthorizationReader reader;
    private final ObjectMapper json = RbacJson.mapper();
    private final ObjectMapper legacyJson = RbacJson.legacyMapper();

    HistoricalRbacAssignmentWriter(AdminAuditJournal managementAudit, RbacMapper mapper,
                                   RbacCatalog catalog, RbacAuthorizationReader reader) {
        super(mapper, reader);
        this.managementAudit = managementAudit;
        this.mapper = mapper;
        this.catalog = catalog;
        this.reader = reader;
    }

    private void lockSubjects(long actor, long target) {
        mapper.lockSubject(Math.min(actor, target));
        if (actor != target) mapper.lockSubject(Math.max(actor, target));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    Receipt grant(Command command) {
        return execute(command, true);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    Receipt revoke(Command command) {
        return execute(command, false);
    }

    private Receipt execute(Command command, boolean grant) {
        validate(command, grant);
        try {
            if (!"ROOT".equals(mapper.lockGovernance())) throw RbacProblems.unavailable();
            lockSubjects(command.actorUserId(), command.targetUserId());
            LocalDateTime now = mapper.now();
            requireRoot(command.actorUserId(), now);
            if (command.actorUserId() == command.targetUserId())
                throw RbacProblems.problem(HttpStatus.FORBIDDEN, "authorization-self-change-forbidden");
            byte[] key = command.idempotencyKey().getBytes(StandardCharsets.US_ASCII);
            String fingerprint = RbacCatalog.hash(encode(new Intent(grant ? "GRANT" : "REVOKE", command)).getBytes(StandardCharsets.UTF_8));
            Change prior = mapper.receipt(command.actorUserId(), key);
            if (prior != null) {
                if (!Integer.valueOf(1).equals(prior.getSnapshotVersion())) throw RbacProblems.unavailable();
                if (!fingerprint.equals(prior.getRequestFingerprint()) && !legacyIntent(command, grant, prior))
                    throw conflict("idempotency-key-conflict");
                readable(command.targetUserId());
                Receipt restored = json.readValue(prior.getReceipt(), Receipt.class);
                if (restored == null || restored.assignment() == null || !prior.getId().equals(restored.changeId())
                        || !prior.getOperationId().equals(restored.operationId()) || !prior.getAction().equals(restored.action())
                        || !prior.getAssignmentId().equals(restored.assignment().getId())
                        || !Long.valueOf(command.targetUserId()).equals(restored.assignment().getUserId()))
                    throw RbacProblems.unavailable();
                Assignment fixed = restored.assignment();
                if (restored.committedAt() == null || restored.authorizationVersion() == null || restored.authorizationVersion().isEmpty()
                        || fixed.getVersion() == null || fixed.getVersion().isEmpty() || fixed.getGrantedAt() == null || fixed.getGrantChangeId() == null
                        || !"GLOBAL".equals(fixed.getScopeType()) || catalog.role(fixed.getRoleCode()) == null
                        || !catalog.role(fixed.getRoleCode()).namespaces().contains(fixed.getNamespace())
                        || "ROOT".equals(fixed.getRoleCode()) && fixed.getExpiresAt() != null
                        || (fixed.getRevokedAt() == null) != (fixed.getRevokeChangeId() == null))
                    throw RbacProblems.unavailable();
                return restored;
            }
            Subject target = readable(command.targetUserId());
            if (!"ASSIGNMENT".equals(target.getSource())) {
                if (!"LEGACY".equals(target.getSource())) throw RbacProblems.unavailable();
                throw conflict("authorization-source-migration-required");
            }
            managementAudit.confirmed(command.actorUserId(), grant ? "grantUserRoleAssignment" : "revokeUserRoleAssignment", "USER", Long.toString(command.targetUserId()));
            if (!command.expectedAuthorizationVersion().equals(target.getVersion()))
                throw conflict("authorization-version-conflict");
            List<Assignment> assignments = mapper.assignments(command.targetUserId());
            Assignment assignment;
            Assignment before = null;
            String changeId = uuid();
            if (grant) {
                if ("ROOT".equals(command.roleCode()) && !mapper.rootUsers().isEmpty())
                    throw conflict("authorization-root-singleton-protected");
                checkGrantable(command);
                now = mapper.now(); // time after waits/validation, never client clock
                if (command.expiresAt() != null && !command.expiresAt().isAfter(now))
                    throw RbacProblems.invalid("expiresAt");
                for (Assignment a : assignments)
                    if (a.getRoleCode().equals(command.roleCode()) && a.getNamespace().equals(command.namespace())
                            && "GLOBAL".equals(a.getScopeType()) && "ACTIVE".equals(a.status(now)))
                        throw conflict("rbac-assignment-already-active");
                mapper.releaseExpired(command.targetUserId(), now);
                assignment = new Assignment();
                assignment.setId(uuid());
                assignment.setUserId(command.targetUserId());
                assignment.setRoleCode(command.roleCode());
                assignment.setNamespace(command.namespace());
                assignment.setScopeType("GLOBAL");
                assignment.setGrantedAt(now);
                assignment.setExpiresAt(command.expiresAt());
                assignment.setVersion(uuid());
                assignment.setGrantChangeId(changeId);
                requireRoot(command.actorUserId(), now);
                if (mapper.insertAssignment(assignment) != 1) throw RbacProblems.unavailable();
            } else {
                assignment = assignments.stream().filter(a -> a.getId().equals(command.assignmentId())).findFirst()
                        .orElseThrow(() -> RbacProblems.problem(HttpStatus.NOT_FOUND, "not-found"));
                if (!command.expectedAssignmentVersion().equals(assignment.getVersion()))
                    throw conflict("authorization-version-conflict");
                now = mapper.now();
                if (!"ACTIVE".equals(assignment.status(now)))
                    throw conflict("REVOKED".equals(assignment.status(now)) ? "rbac-assignment-revoked" : "rbac-assignment-expired");
                requireRoot(command.actorUserId(), now);
                if ("ROOT".equals(assignment.getRoleCode()) && validRootUsers(now).size() <= 1)
                    throw conflict("authorization-last-root-protected");
                before = json.readValue(encode(assignment), Assignment.class);
                String next = uuid();
                if (mapper.revoke(assignment.getId(), assignment.getVersion(), next, changeId, now) != 1)
                    throw conflict("authorization-version-conflict");
                assignment.setVersion(next);
                assignment.setRevokedAt(now);
                assignment.setRevokeChangeId(changeId);
            }
            String next = uuid();
            if (mapper.revise(command.targetUserId(), command.expectedAuthorizationVersion(), next, now) != 1)
                throw conflict("authorization-version-conflict");
            Receipt receipt = new Receipt(changeId, uuid(), grant ? "GRANT" : "REVOKE", now, next, assignment);
            Change change = new Change();
            change.setId(changeId);
            change.setOperationId(receipt.operationId());
            change.setActorUserId(command.actorUserId());
            change.setTargetUserId(command.targetUserId());
            change.setAssignmentId(assignment.getId());
            change.setAction(receipt.action());
            change.setIdempotencyKey(key);
            change.setRequestFingerprint(fingerprint);
            change.setReason(command.reason());
            change.setBeforeSnapshot(before == null ? null : encode(before));
            change.setAfterSnapshot(encode(assignment));
            change.setReceipt(encode(receipt));
            change.setOccurredAt(now);
            if (mapper.insertChange(change) != 1) throw RbacProblems.unavailable();
            managementAudit.operation(changeId, command.actorUserId(), grant ? "grantUserRoleAssignment" : "revokeUserRoleAssignment", "ROLE_ASSIGNMENT", assignment.getId(), now);
            managementAudit.duty(changeId, changeId, command.actorUserId(), "ROLE_ASSIGNMENT", grant ? "GRANT" : "REVOKE", Long.toString(assignment.getUserId()), null,
                    "ROLE_ASSIGNMENT", assignment.getId(), managementAudit.tree(before), managementAudit.tree(assignment), now);
            return receipt;
        } catch (DataAccessException | JsonProcessingException e) {
            log.warn("[RbacGovernanceService][鎺堟潈瀛樺偍澶辫触] operatorId={}, targetUserId={}", command.actorUserId(), command.targetUserId());
            throw RbacProblems.unavailable();
        } catch (cn.jualn.miniapp.common.exception.ContractProblemException e) {
            log.warn("[RbacGovernanceService][鎺堟潈鎷掔粷] operatorId={}, targetUserId={}", command.actorUserId(), command.targetUserId());
            throw e;
        }
    }

    private boolean legacyIntent(Command command, boolean grant, Change prior) throws JsonProcessingException {
        String fingerprint = RbacCatalog.hash(legacyJson.writeValueAsBytes(new Intent(grant ? "GRANT" : "REVOKE", command)));
        if (!fingerprint.equals(prior.getRequestFingerprint())) return false;
        // The old HTTP mapper omitted subsecond expiry from its hash. Compare the immutable stored expiry as well.
        if (grant) {
            Assignment fact = mapper.assignments(command.targetUserId()).stream().filter(a -> a.getId().equals(prior.getAssignmentId()))
                    .findFirst().orElseThrow(RbacProblems::unavailable);
            if (!Objects.equals(command.expiresAt(), fact.getExpiresAt())) return false;
        }
        return true;
    }

    private void checkGrantable(Command command) {
        var permissions = mapper.permissions();
        var roles = mapper.roles();
        catalog.verify(permissions, roles, mapper.members());
        if (java.util.Set.of("ARTICLE_CATEGORY_STEWARD_V1", "POST_DICTIONARY_STEWARD_V1", "POST_MODERATOR_V1", "HOME_BANNER_MANAGER_V1").contains(command.roleCode()))
            throw RbacProblems.problem(HttpStatus.FORBIDDEN, "authorization-grant-forbidden");
        var entry = catalog.role(command.roleCode());
        var role = RbacAuthorizationReader.index(roles).get(command.roleCode());
        if (entry == null || "DISABLED".equals(role.getLifecycle())) throw conflict("rbac-role-not-grantable");
        if (!entry.namespaces().contains(command.namespace()))
            throw RbacProblems.problem(HttpStatus.FORBIDDEN, "authorization-grant-forbidden");
        var permissionIndex = RbacAuthorizationReader.index(permissions);
        if (entry.permissions().stream().anyMatch(p -> RbacCatalog.excluded(p) || "DISABLED".equals(permissionIndex.get(p).getLifecycle())))
            throw conflict("rbac-role-not-grantable");
        if (!catalog.roleReady(entry.code(), RbacAuthorizationReader.index(roles), permissionIndex))
            throw RbacProblems.problem(HttpStatus.SERVICE_UNAVAILABLE, "authorization-capability-not-ready");
        if (!role.isGrantable() || entry.permissions().stream().anyMatch(p -> !permissionIndex.get(p).isGrantable()))
            throw conflict("rbac-role-not-grantable");
    }

    private Subject readable(long target) {
        Subject subject = mapper.subject(target);
        if (subject == null || subject.getDeletedAt() != null)
            throw RbacProblems.problem(HttpStatus.NOT_FOUND, "not-found");
        if (subject.getSource() == null || subject.getVersion() == null) throw RbacProblems.unavailable();
        return subject;
    }

    void requireRoot(long actor, LocalDateTime now) {
        Decision decision = reader.readCurrent(actor, now);
        boolean root = decision.accountEligible() && decision.source() == Source.ASSIGNMENT
                && decision.grants().stream().anyMatch(g -> "ROOT".equals(g.roleCode()) && g.allows("authorization:root-manage", "AUTHORIZATION")
                && g.allows("authorization:assignment-manage", "AUTHORIZATION"));
        if (!root) throw RbacProblems.problem(HttpStatus.FORBIDDEN, "authorization-management-forbidden");
    }

    private Set<Long> validRootUsers(LocalDateTime now) {
        Set<Long> users = new HashSet<>();
        for (long user : mapper.rootUsers()) {
            Decision d = reader.readCurrent(user, now);
            if (d.grants().stream().anyMatch(g -> "ROOT".equals(g.roleCode()) && g.allows("authorization:root-manage", "AUTHORIZATION")))
                users.add(user);
        }
        return users;
    }

    void validate(Command c, boolean grant) {
        if (c == null || c.actorUserId() <= 0 || c.targetUserId() <= 0) throw RbacProblems.invalid("userId");
        if (c.idempotencyKey() == null || !c.idempotencyKey().matches("[A-Za-z0-9][A-Za-z0-9._:-]{15,127}"))
            throw RbacProblems.invalid("Idempotency-Key");
        if (c.reason() == null || !REASON_CONTENT.matcher(c.reason()).find() || c.reason().codePointCount(0, c.reason().length()) > 500)
            throw RbacProblems.invalid("reason");
        if (c.expectedAuthorizationVersion() == null || c.expectedAuthorizationVersion().isEmpty())
            throw RbacProblems.invalid("expectedAuthorizationVersion");
        if (grant) {
            if (c.roleCode() == null || c.roleCode().isEmpty()) throw RbacProblems.invalid("roleCode");
            if (c.namespace() == null || !catalog.namespaceKnown(c.namespace()))
                throw RbacProblems.invalid("namespace");
            if (c.scopeType() == null) throw RbacProblems.invalid("scope");
            if (Set.of("ORGANIZATION", "CATEGORY", "PUBLISHER", "RESOURCE_SET").contains(c.scopeType()))
                throw RbacProblems.problem(HttpStatus.BAD_REQUEST, "rbac-scope-unsupported");
            if (!"GLOBAL".equals(c.scopeType())) throw RbacProblems.invalid("scope");
            if ("ROOT".equals(c.roleCode()) && !"AUTHORIZATION".equals(c.namespace()))
                throw RbacProblems.invalid("namespace");
            if ("ROOT".equals(c.roleCode()) && c.expiresAt() != null) throw RbacProblems.invalid("expiresAt");
            if (c.expiresAt() != null && c.expiresAt().getNano() % 1000 != 0) throw RbacProblems.invalid("expiresAt");
            if (c.assignmentId() != null || c.expectedAssignmentVersion() != null)
                throw RbacProblems.invalid("assignmentId");
        } else if (c.assignmentId() == null || c.expectedAssignmentVersion() == null || c.roleCode() != null || c.namespace() != null || c.scopeType() != null || c.expiresAt() != null)
            throw RbacProblems.invalid("assignmentId");
    }

    private String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw RbacProblems.unavailable();
        }
    }

    private record Intent(String action, Command command) {
    }

    private static String uuid() {
        return UUID.randomUUID().toString();
    }

    private static RuntimeException conflict(String type) {
        return RbacProblems.problem(HttpStatus.CONFLICT, type);
    }
}
