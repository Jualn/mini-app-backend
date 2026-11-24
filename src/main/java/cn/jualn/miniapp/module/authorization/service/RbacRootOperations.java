package cn.jualn.miniapp.module.authorization.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static cn.jualn.miniapp.module.authorization.model.RbacModels.*;
import static cn.jualn.miniapp.module.authorization.service.RbacOfflineTool.*;

/** Runs only from the operator CLI with explicit database credentials and a reviewed plan. */
public final class RbacRootOperations {
    enum Action { BOOTSTRAP, TRANSFER, RECOVERY }

    record Impact(String type, String id, long userId, LocalDateTime expiresAt) {}

    record Plan(String operationId, Action action, String environmentId, String serverUuid, String database,
                String executorRef, String approvalRef, String approvalSha256, Instant approvedUntil,
                String catalogVersion, Long previousUserId, long nextUserId, String previousAssignmentId,
                String previousState, String nextState, List<Impact> dependentAuthorizations) {}

    record Receipt(int version, String operationId, String environmentId, String kind,
                   LocalDateTime committedAt, List<Effect> effects) {}

    private final RbacInitialMigration db;

    RbacRootOperations(RbacInitialMigration db) {
        this.db = db;
    }

    Plan inspect(Action action, String environment, Long previous, long next, String executor,
                 String approval, String approvalSha256) {
        return db.reads.execute(tx -> snapshot(id(), action, environment, previous, next, executor,
                approval, approvalSha256, db.mapper.now().toInstant(ZoneOffset.UTC).plusSeconds(1800)));
    }

    void dryRun(Plan plan) {
        db.reads.executeWithoutResult(tx -> validateCurrent(plan));
    }

    Receipt execute(Plan plan) {
        return db.writes.execute(tx -> {
            Long epoch = db.jdbc.queryForObject("SELECT epoch FROM person_authority_epoch WHERE id=1 FOR UPDATE", Long.class);
            if (epoch == null || !"ROOT".equals(db.mapper.lockGovernance())) throw rejected("Governance locks unavailable");
            validateIdentity(plan);
            var users = new ArrayList<Long>();
            users.add(plan.nextUserId());
            if (plan.previousUserId() != null) users.add(plan.previousUserId());
            users.stream().distinct().sorted().forEach(db.mapper::lockSubject);
            var prior = db.offline.operation(plan.operationId());
            if (prior != null) return restore(plan, prior);
            validateCurrent(plan);

            if (plan.action() == Action.BOOTSTRAP && !rootReady(db.mapper.permissions(), db.mapper.roles())) publishRoot();
            var now = db.mapper.now();
            var effects = new ArrayList<Effect>();
            if (plan.previousUserId() != null) {
                var before = db.state(plan.previousUserId());
                var previous = db.mapper.assignment(plan.previousAssignmentId());
                String effectId = id();
                requireOne(db.mapper.revoke(previous.getId(), previous.getVersion(), id(), effectId, now));
                revise(before, now);
                effects.add(new Effect(effectId, plan.previousUserId(), null, before, db.state(plan.previousUserId())));
            }
            var before = db.state(plan.nextUserId());
            var assignment = new Assignment();
            assignment.setId(id());
            assignment.setUserId(plan.nextUserId());
            assignment.setRoleCode("ROOT");
            assignment.setNamespace("AUTHORIZATION");
            assignment.setScopeType("GLOBAL");
            assignment.setGrantedAt(now);
            assignment.setVersion(id());
            assignment.setGrantChangeId(id());
            requireOne(db.mapper.insertAssignment(assignment));
            revise(before, now);
            effects.add(new Effect(assignment.getGrantChangeId(), plan.nextUserId(), assignment.getId(),
                    before, db.state(plan.nextUserId())));
            var receipt = new Receipt(4, plan.operationId(), plan.environmentId(), plan.action().name(), now, List.copyOf(effects));
            persist(plan, receipt);
            requireOne(db.jdbc.update("UPDATE person_authority_epoch SET epoch=epoch+1 WHERE id=1 AND epoch=?", epoch));
            if (!db.mapper.rootUsers().equals(List.of(plan.nextUserId()))) throw rejected("Unique ROOT check failed");
            RbacOfflineAudit.verify(db.mapper, assignment);
            if (!rootReady(db.mapper.permissions(), db.mapper.roles())) throw rejected("ROOT is not ready after the operation");
            return receipt;
        });
    }

    private Plan snapshot(String operationId, Action action, String environment, Long previous, long next,
                          String executor, String approval, String approvalSha, Instant until) {
        if (action == null || next <= 0 || previous != null && (previous <= 0 || previous == next)
                || !text(environment, 96) || !text(executor, 128) || !text(approval, 500)
                || approvalSha == null || !approvalSha.matches("[a-f0-9]{64}"))
            throw rejected("Explicit target, environment, executor and approval evidence required");
        var now = db.mapper.now();
        if (!until.isAfter(now.toInstant(ZoneOffset.UTC))) throw rejected("Plan expired");
        if (!db.offline.serverVersion().matches("8[.]4[.].*")) throw rejected("MySQL 8.4 required");
        var coverage = db.mapper.coverage();
        if (coverage.missing() != 0 || coverage.corrupt() != 0 || coverage.legacy() != 0)
            throw rejected("Complete ASSIGNMENT authority coverage required");

        var permissions = db.mapper.permissions();
        var roles = db.mapper.roles();
        db.catalog.verify(permissions, roles, db.mapper.members());
        if (!rootReady(permissions, roles)) {
            var root = RbacAuthorizationReader.index(roles).get("ROOT");
            var definitions = RbacAuthorizationReader.index(permissions);
            var members = db.catalog.role("ROOT").permissions();
            if (action != Action.BOOTSTRAP || !untouched(root)
                    || !db.catalog.supports("AUTHORIZATION", members)
                    || members.stream().anyMatch(code -> !untouched(definitions.get(code))))
                throw rejected("ROOT catalog must be ready or completely untouched for initialization");
        }

        var nextState = db.state(next);
        if (!"ASSIGNMENT".equals(nextState.subject().getSource()) || nextState.subject().getDeletedAt() != null
                || !Integer.valueOf(1).equals(nextState.subject().getStatus()))
            throw rejected("Replacement must be a normal ASSIGNMENT account");

        var roots = db.mapper.rootUsers();
        long history = db.offline.rootHistory();
        switch (action) {
            case BOOTSTRAP -> {
                if (history != 0 || previous != null || !roots.isEmpty())
                    throw rejected("Initialization requires no ROOT history and no outgoing account");
            }
            case TRANSFER -> {
                if (previous == null || !roots.equals(List.of(previous))
                        || !RbacAuthorizationReader.accountEligible(db.state(previous).subject(), now))
                    throw rejected("Transfer requires the current eligible ROOT as outgoing account");
            }
            case RECOVERY -> {
                if (history == 0 || !(previous == null ? roots.isEmpty() : roots.equals(List.of(previous))))
                    throw rejected("Recovery requires ROOT history and an exact match to the outstanding ROOT");
            }
        }
        State previousState = previous == null ? null : db.state(previous);
        Assignment previousAssignment = null;
        if (previousState != null) {
            var candidates = previousState.assignments().stream()
                    .filter(a -> "ROOT".equals(a.getRoleCode()) && a.getRevokedAt() == null).toList();
            if (candidates.size() != 1 || !"ASSIGNMENT".equals(previousState.subject().getSource()))
                throw rejected("Outgoing ROOT fact is ambiguous");
            previousAssignment = candidates.get(0);
            if (!"AUTHORIZATION".equals(previousAssignment.getNamespace()) || !"GLOBAL".equals(previousAssignment.getScopeType())
                    || !"STANDALONE".equals(previousAssignment.getOriginKind())
                    || previousAssignment.getExpiresAt() != null || previousAssignment.getGrantedAt().isAfter(now))
                throw rejected("Outgoing ROOT fact is malformed");
            RbacOfflineAudit.verify(db.mapper, previousAssignment);
        }
        String previousId = previousAssignment == null ? null : previousAssignment.getId();
        return new Plan(operationId, action, environment, db.serverUuid(), db.database(), executor, approval, approvalSha,
                until, db.catalog.runtimeVersion(permissions, roles), previous, next, previousId,
                previousState == null ? null : fingerprint(previousState), fingerprint(nextState), impacts(previousId, now));
    }

    private boolean rootReady(List<Definition> permissions, List<Definition> roles) {
        return db.catalog.roleReady("ROOT", RbacAuthorizationReader.index(roles), RbacAuthorizationReader.index(permissions));
    }

    private boolean untouched(Definition row) {
        return row != null && "REGISTERED".equals(row.getLifecycle()) && "NOT_READY".equals(row.getReadiness())
                && !row.isGrantable() && "PROVIDER_NOT_VERIFIED".equals(row.getUnavailableReason());
    }

    private void publishRoot() {
        for (String code : db.catalog.role("ROOT").permissions())
            requireOne(db.jdbc.update("""
                    UPDATE rbac_permission_definition
                    SET lifecycle='ENABLED', readiness='READY', grantable=1, unavailable_reason=NULL
                    WHERE code=? AND lifecycle='REGISTERED' AND readiness='NOT_READY' AND grantable=0
                      AND unavailable_reason='PROVIDER_NOT_VERIFIED'
                    """, code));
        requireOne(db.jdbc.update("""
                UPDATE rbac_role SET lifecycle='ENABLED', readiness='READY', grantable=1, unavailable_reason=NULL
                WHERE code='ROOT' AND lifecycle='REGISTERED' AND readiness='NOT_READY' AND grantable=0
                  AND unavailable_reason='PROVIDER_NOT_VERIFIED'
                """));
    }

    private List<Impact> impacts(String root, LocalDateTime now) {
        if (root == null) return List.of();
        return db.jdbc.query("""
                SELECT 'DELEGATION' AS source_type, id, grantee_user_id, expires_at
                FROM rbac_delegation_authority
                WHERE root_assignment_id=? AND revoked_at IS NULL AND expires_at>?
                UNION ALL
                SELECT s.duty_code, s.id, s.grantee_user_id, s.expires_at
                FROM person_business_scope s
                JOIN rbac_user_role_assignment a ON a.id=s.assignment_id
                LEFT JOIN rbac_delegation_authority d ON d.id=s.authority_id
                WHERE s.revoked_at IS NULL AND s.expires_at>? AND a.revoked_at IS NULL
                  AND (s.root_assignment_id=? OR (d.root_assignment_id=? AND d.revoked_at IS NULL AND d.expires_at>?))
                ORDER BY source_type,id
                """, (rs, row) -> new Impact(rs.getString(1), rs.getString(2), rs.getLong(3),
                rs.getObject(4, LocalDateTime.class)), root, now, now, root, root, now);
    }

    private void validateIdentity(Plan plan) {
        if (plan == null || !UUID.fromString(plan.operationId()).toString().equals(plan.operationId())
                || !db.serverUuid().equals(plan.serverUuid()) || !db.database().equals(plan.database()))
            throw rejected("Plan target database mismatch");
    }

    private void validateCurrent(Plan plan) {
        validateIdentity(plan);
        var current = snapshot(plan.operationId(), plan.action(), plan.environmentId(), plan.previousUserId(),
                plan.nextUserId(), plan.executorRef(), plan.approvalRef(), plan.approvalSha256(), plan.approvedUntil());
        if (!plan.equals(current)) throw rejected("ROOT, catalog, account or dependent authorizations changed. Inspect again.");
    }

    private void revise(State before, LocalDateTime now) {
        var subject = before.subject();
        requireOne(db.mapper.revise(subject.getUserId(), subject.getVersion(), id(), now));
    }

    private void persist(Plan plan, Receipt receipt) {
        var operation = new StoredOperation();
        operation.setId(plan.operationId());
        operation.setEnvironmentId(plan.environmentId());
        operation.setKind(plan.action().name());
        operation.setExecutorRef(plan.executorRef());
        operation.setApprovalRef(plan.approvalRef());
        operation.setApprovalSha256(plan.approvalSha256());
        operation.setRequestFingerprint(fingerprint(plan));
        operation.setRequestJson(encode(plan));
        operation.setApprovalsJson(encode(List.of(Map.of("method", "OPERATOR_MANIFEST_SHA256",
                "approvalRef", plan.approvalRef(), "approvalSha256", plan.approvalSha256()))));
        operation.setSnapshotVersion(4);
        operation.setReceipt(encode(receipt));
        operation.setOccurredAt(receipt.committedAt());
        requireOne(db.offline.insertOperation(operation));
        for (var effect : receipt.effects())
            requireOne(db.offline.insertEffect(plan.operationId(), effect, encode(effect.before()), encode(effect.after())));
    }

    private Receipt restore(Plan plan, StoredOperation prior) {
        if (!Integer.valueOf(4).equals(prior.getSnapshotVersion()) || !fingerprint(plan).equals(prior.getRequestFingerprint()))
            throw rejected("Operation ID semantic conflict");
        try {
            var json = RbacJson.mapper();
            var receipt = json.readValue(prior.getReceipt(), Receipt.class);
            var facts = db.offline.effects(plan.operationId());
            if (receipt.version() != 4 || !plan.operationId().equals(receipt.operationId())
                    || !plan.environmentId().equals(receipt.environmentId()) || !plan.action().name().equals(receipt.kind())
                    || !Objects.equals(prior.getOccurredAt(), receipt.committedAt())
                    || facts.size() != (plan.previousUserId() == null ? 1 : 2) || facts.size() != receipt.effects().size())
                throw rejected("Stored receipt is inconsistent");
            for (var effect : receipt.effects()) {
                var fact = facts.stream().filter(f -> f.id().equals(effect.id())).findFirst().orElseThrow();
                if (fact.userId() != effect.userId() || !Objects.equals(fact.assignmentId(), effect.assignmentId())
                        || !json.readTree(fact.beforeSnapshot()).equals(json.readTree(encode(effect.before())))
                        || !json.readTree(fact.afterSnapshot()).equals(json.readTree(encode(effect.after()))))
                    throw rejected("Stored operation effect is inconsistent");
            }
            var grant = receipt.effects().stream().filter(e -> e.userId() == plan.nextUserId()).findFirst().orElseThrow();
            var assignment = db.mapper.assignment(grant.assignmentId());
            if (assignment == null) throw rejected("Missing ROOT assignment");
            RbacOfflineAudit.verify(db.mapper, assignment);
            return receipt;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Stored ROOT receipt is unreadable", e);
        }
    }

    private List<Impact> independentAssignments() {
        return db.reads.execute(tx -> db.jdbc.query("""
                SELECT role_code, id, user_id, expires_at
                FROM rbac_user_role_assignment
                WHERE origin_kind='STANDALONE' AND role_code<>'ROOT' AND revoked_at IS NULL
                  AND granted_at<=UTC_TIMESTAMP(6) AND (expires_at IS NULL OR expires_at>UTC_TIMESTAMP(6))
                ORDER BY user_id, role_code, id
                """, (rs, row) -> new Impact(rs.getString(1), rs.getString(2), rs.getLong(3),
                rs.getObject(4, LocalDateTime.class))));
    }

    private static void requireOne(int rows) {
        if (rows != 1) throw rejected("Authority, assignment or audit write conflict");
    }

    private static boolean text(String value, int limit) {
        return value != null && !value.isBlank() && value.length() <= limit;
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }

    private static IllegalStateException rejected(String message) {
        return new IllegalStateException(message);
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) throw rejected("Usage: <loopback-jdbc-url> inventory | inspect ... | <plan.json> [--execute <sha256>]");
        String user = Objects.requireNonNull(System.getenv("RBAC_INITIAL_DB_USER"), "RBAC_INITIAL_DB_USER required");
        String password = Objects.requireNonNull(System.getenv("RBAC_INITIAL_DB_PASSWORD"), "RBAC_INITIAL_DB_PASSWORD required");
        var ds = new DriverManagerDataSource(args[0] + "?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true", user, password);
        var tool = new RbacRootOperations(new RbacInitialMigration(args[0], ds, new RbacCatalog()));
        if ("inventory".equals(args[1]) && args.length == 2) {
            System.out.println(encode(tool.independentAssignments()));
        } else if ("inspect".equals(args[1]) && args.length == 9) {
            Long previous = "-".equals(args[4]) ? null : Long.parseLong(args[4]);
            System.out.println(encode(tool.inspect(Action.valueOf(args[2]), args[3], previous, Long.parseLong(args[5]),
                    args[6], args[7], RbacCatalog.hash(Files.readAllBytes(Path.of(args[8]))))));
        } else {
            byte[] bytes = Files.readAllBytes(Path.of(args[1]));
            var plan = RbacJson.mapper().readValue(bytes, Plan.class);
            if (args.length == 2) {
                tool.dryRun(plan);
                System.out.println("DRY_RUN_PASS planSha256=" + RbacCatalog.hash(bytes));
            } else if (args.length == 4 && "--execute".equals(args[2]) && RbacCatalog.hash(bytes).equals(args[3])) {
                System.out.println(encode(tool.execute(plan)));
            } else {
                throw rejected("Execution requires the exact reviewed plan SHA-256");
            }
        }
    }
}
