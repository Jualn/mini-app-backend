package cn.jualn.miniapp.module.authorization.service;

import cn.jualn.miniapp.module.admin.auth.support.AdminPermissionPolicy;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;

import static cn.jualn.miniapp.module.authorization.model.RbacModels.*;
import static cn.jualn.miniapp.module.authorization.service.RbacOfflineTool.*;

/**
 * Explicit correction of the sole untouched v2 initial migration; never impersonates a ROOT session.
 */
public final class RbacInitialCorrection {
    public record Plan(int version, String operationId, String learningOperationId, String environmentId,
                       String jdbcUrl,
                       String serverUuid, String database, String initialOperationId, long wrongRootId,
                       long correctRootId,
                       String executorRef, String approvalRef, String approvalSha256, String approvedUntil,
                       String catalogVersion,
                       State wrongBefore, State correctBefore) {
    }

    public record Receipt(int version, String operationId, String environmentId, String kind, LocalDateTime committedAt,
                          List<Effect> effects) {
    }

    public record Result(Receipt rootCorrection, Receipt learningGrant) {
    }

    private final RbacInitialMigration db;

    RbacInitialCorrection(String url, DataSource ds) throws Exception {
        db = new RbacInitialMigration(url, ds);
    }

    public Plan inspect(String environment, long wrong, long correct, String executor, String approvalRef, String approvalSha) {
        return db.reads.execute(tx -> {
            State prior = state(wrong), target = state(correct);
            if (prior.assignments().size() != 1) throw rejected("Exactly one initial ROOT required");
            String origin = db.jdbc.queryForObject("SELECT o.id FROM rbac_offline_operation o JOIN rbac_offline_effect e ON e.operation_id=o.id WHERE e.assignment_id=? AND o.kind='BOOTSTRAP' AND o.snapshot_version=2", String.class, prior.assignments().get(0).getId());
            Plan p = new Plan(3, UUID.randomUUID().toString(), UUID.randomUUID().toString(), environment, db.url, server(), database(), origin,
                    wrong, correct, executor, approvalRef, approvalSha, Instant.now().plusSeconds(1800).toString(), catalogVersion(), prior, target);
            validateNew(p);
            return p;
        });
    }

    public void dryRun(Plan p) {
        db.reads.executeWithoutResult(tx -> validateNew(p));
    }

    public Result execute(Plan p) {
        identity(p);
        return db.writes.execute(tx -> {
            if (!"ROOT".equals(db.mapper.lockGovernance())) throw rejected("ROOT mutex missing");
            db.mapper.lockSubject(Math.min(p.wrongRootId(), p.correctRootId()));
            db.mapper.lockSubject(Math.max(p.wrongRootId(), p.correctRootId()));
            var first = db.offline.operation(p.operationId());
            var second = db.offline.operation(p.learningOperationId());
            if (first != null || second != null) {
                if (first == null || second == null) throw rejected("Incomplete correction operation pair");
                Result result = new Result(restore(p, first, "CORRECT_INITIAL_ROOT", 2), restore(p, second, "INITIAL_ROLE_GRANT", 1));
                Effect removed = result.rootCorrection().effects().stream().filter(e -> e.userId() == p.wrongRootId()).findFirst().orElseThrow(() -> rejected("Missing mistaken ROOT correction"));
                Effect granted = result.rootCorrection().effects().stream().filter(e -> e.userId() == p.correctRootId()).findFirst().orElseThrow(() -> rejected("Missing correct ROOT effect"));
                Effect learned = result.learningGrant().effects().get(0);
                if (removed.assignmentId() != null || granted.assignmentId() == null || learned.assignmentId() == null
                        || !removed.before().equals(p.wrongBefore()) || !granted.before().equals(p.correctBefore())
                        || learned.userId() != p.correctRootId() || !learned.before().equals(granted.after()))
                    throw rejected("Correction effect chain mismatch");
                RbacOfflineAudit.verify(db.mapper, db.mapper.assignments(p.wrongRootId()));
                RbacOfflineAudit.verify(db.mapper, db.mapper.assignments(p.correctRootId()));
                return result;
            }
            validateNew(p);
            var now = db.mapper.now();
            String removedEffect = UUID.randomUUID().toString(), rootEffect = UUID.randomUUID().toString();
            // Add the correct ROOT before revoking the mistaken one, in this same guarded transaction.
            Assignment root = grant(p.correctRootId(), "ROOT", "AUTHORIZATION", rootEffect, now);
            Assignment wrong = p.wrongBefore().assignments().get(0);
            if (db.mapper.revoke(wrong.getId(), wrong.getVersion(), UUID.randomUUID().toString(), removedEffect, now) != 1)
                throw rejected("Initial ROOT changed");
            revise(p.wrongRootId(), p.wrongBefore().subject().getVersion(), now);
            revise(p.correctRootId(), p.correctBefore().subject().getVersion(), now);
            List<Effect> effects = new ArrayList<>(List.of(new Effect(removedEffect, p.wrongRootId(), null, p.wrongBefore(), state(p.wrongRootId())),
                    new Effect(rootEffect, p.correctRootId(), root.getId(), p.correctBefore(), state(p.correctRootId()))));
            effects.sort(Comparator.comparingLong(Effect::userId));
            Receipt corrected = new Receipt(3, p.operationId(), p.environmentId(), "CORRECT_INITIAL_ROOT", now, List.copyOf(effects));
            persist(p, corrected);
            State beforeLearning = state(p.correctRootId());
            String learningEffect = UUID.randomUUID().toString();
            var later = db.mapper.now();
            Assignment manager = grant(p.correctRootId(), "LEARNING_MANAGER", "LEARNING_TASK", learningEffect, later);
            revise(p.correctRootId(), beforeLearning.subject().getVersion(), later);
            Receipt learned = new Receipt(3, p.learningOperationId(), p.environmentId(), "INITIAL_ROLE_GRANT", later,
                    List.of(new Effect(learningEffect, p.correctRootId(), manager.getId(), beforeLearning, state(p.correctRootId()))));
            persist(p, learned);
            var decision = new RbacAuthorizationReader(db.mapper, db.catalog, new AdminPermissionPolicy()).readCurrent(p.correctRootId(), db.mapper.now());
            if (!decision.allows("authorization:root-manage", "AUTHORIZATION") || !decision.allows("learning-task:publish", "LEARNING_TASK")
                    || !decision.allows("learning-task:assign", "LEARNING_TASK"))
                throw rejected("Corrected authorization decision failed");
            return new Result(corrected, learned);
        });
    }

    private void identity(Plan p) {
        if (p == null || p.version() != 3 || p.wrongRootId() <= 0 || p.correctRootId() <= 0 || p.wrongRootId() == p.correctRootId()
                || !db.url.equals(p.jdbcUrl()) || !server().equals(p.serverUuid()) || !database().equals(p.database())
                || !UUID.fromString(p.operationId()).toString().equals(p.operationId())
                || !UUID.fromString(p.learningOperationId()).toString().equals(p.learningOperationId()) || p.operationId().equals(p.learningOperationId())
                || p.environmentId() == null || p.environmentId().isBlank() || p.environmentId().length() > 96
                || p.executorRef() == null || p.executorRef().isBlank() || p.executorRef().length() > 128
                || p.approvalRef() == null || p.approvalRef().isBlank() || p.approvalRef().length() > 500
                || p.approvalSha256() == null || !p.approvalSha256().matches("[a-f0-9]{64}"))
            throw rejected("Explicit approved correction identity required");
    }

    private void validateNew(Plan p) {
        identity(p);
        String versionCheck = db.jdbc.queryForObject("SELECT CHECK_CLAUSE FROM information_schema.check_constraints WHERE constraint_schema=DATABASE() AND constraint_name='ck_rbac_offline_version'", String.class);
        if (versionCheck == null || !versionCheck.contains("3")) throw rejected("V46 required");
        if (!Instant.parse(p.approvedUntil()).isAfter(db.mapper.now().toInstant(ZoneOffset.UTC)) || !catalogVersion().equals(p.catalogVersion()))
            throw rejected("Plan expired or catalog changed");
        if (!encode(state(p.wrongRootId())).equals(encode(p.wrongBefore())) || !encode(state(p.correctRootId())).equals(encode(p.correctBefore())))
            throw rejected("Subject or grant changed");
        var coverage = db.mapper.coverage();
        if (coverage.missing() != 0 || coverage.corrupt() != 0) throw rejected("Authority coverage incomplete");
        if (db.jdbc.queryForObject("SELECT COUNT(*) FROM rbac_user_role_assignment", Long.class) != 1
                || db.jdbc.queryForObject("SELECT COUNT(*) FROM rbac_authorization_change", Long.class) != 0
                || db.jdbc.queryForObject("SELECT COUNT(*) FROM rbac_offline_operation", Long.class) != 1)
            throw rejected("Only untouched mistaken initial migration can be corrected here");
        var initial = db.offline.operation(p.initialOperationId());
        if (initial == null || !Integer.valueOf(2).equals(initial.getSnapshotVersion()) || !"BOOTSTRAP".equals(initial.getKind()))
            throw rejected("Known v2 initial operation required");
        Assignment wrong = p.wrongBefore().assignments().get(0);
        if (wrong.getUserId() != p.wrongRootId() || !"ROOT".equals(wrong.getRoleCode()) || !"AUTHORIZATION".equals(wrong.getNamespace())
                || wrong.getRevokedAt() != null || wrong.getExpiresAt() != null || !"GLOBAL".equals(wrong.getScopeType()))
            throw rejected("Mistaken initial ROOT must still be unchanged");
        if (db.offline.effects(p.initialOperationId()).stream().noneMatch(e -> e.userId() == p.wrongRootId() && wrong.getId().equals(e.assignmentId()) && wrong.getGrantChangeId().equals(e.id())))
            throw rejected("Initial grant origin mismatch");
        Subject target = p.correctBefore().subject();
        if (!"ASSIGNMENT".equals(target.getSource()) || !Integer.valueOf(1).equals(target.getStatus()) || target.getDeletedAt() != null || !p.correctBefore().assignments().isEmpty())
            throw rejected("Correct ROOT must be a current normal unassigned account");
        for (String role : List.of("ROOT", "LEARNING_MANAGER")) {
            var ps = RbacAuthorizationReader.index(db.mapper.permissions());
            var rs = RbacAuthorizationReader.index(db.mapper.roles());
            if (!db.catalog.roleReady(role, rs, ps) || !rs.get(role).isGrantable() || db.catalog.role(role).permissions().stream().anyMatch(c -> !ps.get(c).isGrantable()))
                throw rejected("Complete role readiness required");
        }
        RbacOfflineAudit.verify(db.mapper, wrong);
    }

    private Assignment grant(long user, String role, String namespace, String effect, LocalDateTime now) {
        Assignment a = new Assignment();
        a.setId(UUID.randomUUID().toString());
        a.setUserId(user);
        a.setRoleCode(role);
        a.setNamespace(namespace);
        a.setScopeType("GLOBAL");
        a.setGrantedAt(now);
        a.setVersion(UUID.randomUUID().toString());
        a.setGrantChangeId(effect);
        if (db.mapper.insertAssignment(a) != 1) throw rejected("Role insert failed");
        return a;
    }

    private void revise(long user, String version, LocalDateTime now) {
        if (db.mapper.revise(user, version, UUID.randomUUID().toString(), now) != 1)
            throw rejected("Authority version conflict");
    }

    private State state(long user) {
        Subject s = db.mapper.subject(user);
        if (s == null || s.getVersion() == null) throw rejected("Existing explicit authority required");
        return new State(s, List.copyOf(db.mapper.assignments(user)));
    }

    private String server() {
        return db.jdbc.queryForObject("SELECT @@server_uuid", String.class);
    }

    private String database() {
        return db.jdbc.queryForObject("SELECT DATABASE()", String.class);
    }

    private String catalogVersion() {
        var ps = db.mapper.permissions();
        var rs = db.mapper.roles();
        db.catalog.verify(ps, rs, db.mapper.members());
        return db.catalog.runtimeVersion(ps, rs);
    }

    private String approvals(Plan p) {
        return encode(List.of(Map.of("method", "OPERATOR_MANIFEST_SHA256", "executorRef", p.executorRef(), "approvalRef", p.approvalRef(), "approvalSha256", p.approvalSha256(), "planFingerprint", fingerprint(p))));
    }

    private void persist(Plan p, Receipt receipt) {
        var o = new StoredOperation();
        o.setId(receipt.operationId());
        o.setEnvironmentId(p.environmentId());
        o.setKind(receipt.kind());
        o.setExecutorRef(p.executorRef());
        o.setApprovalRef(p.approvalRef());
        o.setApprovalSha256(p.approvalSha256());
        o.setRequestFingerprint(fingerprint(p));
        o.setRequestJson(encode(p));
        o.setApprovalsJson(approvals(p));
        o.setSnapshotVersion(3);
        o.setReceipt(encode(receipt));
        o.setOccurredAt(receipt.committedAt());
        if (db.offline.insertOperation(o) != 1) throw rejected("Correction audit insert failed");
        for (Effect e : receipt.effects())
            if (db.offline.insertEffect(receipt.operationId(), e, encode(e.before()), encode(e.after())) != 1)
                throw rejected("Correction effect insert failed");
    }

    private Receipt restore(Plan p, StoredOperation o, String kind, int count) {
        try {
            if (!Integer.valueOf(3).equals(o.getSnapshotVersion()) || !kind.equals(o.getKind()) || !fingerprint(p).equals(o.getRequestFingerprint())
                    || !p.equals(RbacJson.mapper().readValue(o.getRequestJson(), Plan.class)) || !p.environmentId().equals(o.getEnvironmentId())
                    || !p.executorRef().equals(o.getExecutorRef()) || !p.approvalRef().equals(o.getApprovalRef()) || !p.approvalSha256().equals(o.getApprovalSha256())
                    || !RbacJson.mapper().readTree(approvals(p)).equals(RbacJson.mapper().readTree(o.getApprovalsJson())))
                throw rejected("Correction identity or approval conflict");
            Receipt receipt = RbacJson.mapper().readValue(o.getReceipt(), Receipt.class);
            var facts = db.offline.effects(o.getId());
            if (receipt.version() != 3 || !receipt.operationId().equals(o.getId()) || !receipt.environmentId().equals(p.environmentId()) || !kind.equals(receipt.kind())
                    || !Objects.equals(receipt.committedAt(), o.getOccurredAt()) || receipt.effects().size() != count || facts.size() != count)
                throw rejected("Incomplete correction receipt");
            for (int i = 0; i < count; i++) {
                var e = receipt.effects().get(i);
                var f = facts.get(i);
                if (e.userId() != f.userId() || !e.id().equals(f.id()) || !Objects.equals(e.assignmentId(), f.assignmentId())
                        || !e.before().equals(RbacJson.mapper().readValue(f.beforeSnapshot(), State.class)) || !e.after().equals(RbacJson.mapper().readValue(f.afterSnapshot(), State.class)))
                    throw rejected("Corrupt correction effect");
            }
            return receipt;
        } catch (IOException ex) {
            throw new IllegalStateException("Corrupt correction fact", ex);
        }
    }

    private static IllegalStateException rejected(String message) {
        return new IllegalStateException(message);
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2)
            throw new IllegalArgumentException("Usage: <url> inspect <environment> <wrong-id> <correct-id> <executor> <approval-ref> <approval-file>; or <url> <plan> [--execute <exact-plan-sha256>]");
        var ds = new DriverManagerDataSource(args[0] + "?sslMode=DISABLED&allowPublicKeyRetrieval=true&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true", Objects.requireNonNull(System.getenv("RBAC_INITIAL_DB_USER")), Objects.requireNonNull(System.getenv("RBAC_INITIAL_DB_PASSWORD")));
        var tool = new RbacInitialCorrection(args[0], ds);
        if ("inspect".equals(args[1])) {
            if (args.length != 8) throw rejected("Explicit correction inspect fields required");
            System.out.println(encode(tool.inspect(args[2], Long.parseLong(args[3]), Long.parseLong(args[4]), args[5], args[6], RbacCatalog.hash(Files.readAllBytes(Path.of(args[7]))))));
        } else {
            byte[] bytes = Files.readAllBytes(Path.of(args[1]));
            Plan p = RbacJson.mapper().readValue(bytes, Plan.class);
            if (args.length == 2) {
                tool.dryRun(p);
                System.out.println("DRY_RUN_PASS planSha256=" + RbacCatalog.hash(bytes));
            } else if (args.length == 4 && "--execute".equals(args[2]) && RbacCatalog.hash(bytes).equals(args[3]))
                System.out.println(encode(tool.execute(p)));
            else throw rejected("Exact approved plan SHA-256 required");
        }
    }
}
