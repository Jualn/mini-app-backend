package cn.jualn.miniapp.module.authorization.service;

import cn.jualn.miniapp.module.authorization.mapper.RbacMapper;

import cn.jualn.miniapp.module.admin.auth.support.AdminPermissionPolicy;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;

import static cn.jualn.miniapp.module.authorization.model.RbacModels.*;
import static cn.jualn.miniapp.module.authorization.service.RbacOfflineTool.*;

/**
 * Standalone operator-owned first migration. Never registered with the application.
 */
public final class RbacInitialMigration {
    public record Plan(int version, String operationId, String environmentId, String jdbcUrl, String serverUuid,
                       String database, long rootUserId, String executorRef, String approvalRef, String approvalSha256,
                       String approvedUntil, String catalogDigest, String catalogBeforeFingerprint,
                       Target target, State before, List<State> ordinaryUsers) {
        public Plan {
            ordinaryUsers = ordinaryUsers == null ? List.of() : List.copyOf(ordinaryUsers);
        }
    }

    final String url;
    final JdbcTemplate jdbc;
    final RbacMapper mapper;
    final RbacOfflineMapper offline;
    final RbacCatalog catalog;
    final TransactionTemplate writes;
    final TransactionTemplate reads;

    RbacInitialMigration(String url, DataSource ds) throws Exception {
        this(url, ds, new RbacCatalog(RbacCatalog.LEARNING_TASK_INTEGRATION_SUPPORT));
    }

    RbacInitialMigration(String url, DataSource ds, RbacCatalog catalog) throws Exception {
        this.catalog = catalog;
        if (url == null || !url.matches("jdbc:mysql://(?:127[.]0[.]0[.]1|localhost):[0-9]{1,5}/[A-Za-z0-9_]+"))
            throw new IllegalArgumentException("Explicit operator-host loopback port and database required; remote URLs and URL credentials/options forbidden");
        this.url = url;
        jdbc = new JdbcTemplate(ds);
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.setCacheEnabled(false);
        config.addMapper(RbacMapper.class);
        config.addMapper(RbacOfflineMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(ds);
        factory.setConfiguration(config);
        var globalConfig = new com.baomidou.mybatisplus.core.config.GlobalConfig();
        globalConfig.setBanner(false);
        factory.setGlobalConfig(globalConfig);
        factory.setMapperLocations(new ClassPathResource("mapper/RbacMapper.xml"), new ClassPathResource("mapper/RbacOfflineMapper.xml"));
        var session = new SqlSessionTemplate(Objects.requireNonNull(factory.getObject()));
        mapper = session.getMapper(RbacMapper.class);
        offline = session.getMapper(RbacOfflineMapper.class);
        var manager = new DataSourceTransactionManager(ds);
        writes = new TransactionTemplate(manager);
        writes.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        writes.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        writes.setTimeout(30);
        reads = new TransactionTemplate(manager);
        reads.setReadOnly(true);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        reads.setTimeout(30);
    }

    public Plan inspect(String environmentId, long root, String executor, String approvalRef, String approvalSha256) {
        if (environmentId == null || environmentId.isBlank() || environmentId.length() > 96 || root <= 0
                || executor == null || executor.isBlank() || executor.length() > 128
                || approvalRef == null || approvalRef.isBlank() || approvalRef.length() > 500
                || approvalSha256 == null || !approvalSha256.matches("[a-f0-9]{64}"))
            throw rejected("Explicit approval and target required");
        return reads.execute(tx -> {
            State before = state(root);
            List<Long> ordinary = jdbc.queryForList("SELECT user_id FROM rbac_authorization_authority WHERE user_id<>? ORDER BY user_id LIMIT 1001", Long.class, root);
            if (ordinary.size() >= 1000)
                throw rejected("Initial migration is bounded to 1000 accounts; larger cohorts require an approved batch procedure");
            Plan plan = new Plan(2, UUID.randomUUID().toString(), environmentId, url, serverUuid(), database(), root, executor,
                    approvalRef, approvalSha256, Instant.now().plusSeconds(1800).toString(), catalog.digest(), catalogFingerprint(),
                    new Target(root, before.subject().getVersion(), fingerprint(before.subject())), before,
                    ordinary.stream().map(this::state).toList());
            validateNew(plan);
            return plan;
        });
    }

    public void dryRun(Plan plan) {
        reads.executeWithoutResult(tx -> {
            validateIdentity(plan);
            validateNew(plan);
        });
    }

    public Result execute(Plan plan) {
        validateIdentity(plan);
        return writes.execute(tx -> {
            if (!"ROOT".equals(mapper.lockGovernance())) throw rejected("ROOT mutex missing");
            validateIdentity(plan);
            targets(plan).stream().map(s -> s.subject().getUserId()).sorted().forEach(mapper::lockSubject);
            var prior = offline.operation(plan.operationId());
            if (prior != null) return restore(plan, prior);
            validateNew(plan);
            State before = state(plan.rootUserId());
            // Definitions are activated only in this approved transaction; other catalog entries stay untouched.
            for (String code : RbacCatalog.LEARNING_TASK_INTEGRATION_SUPPORT) {
                if (jdbc.update("UPDATE rbac_permission_definition SET lifecycle='ENABLED',readiness='READY',grantable=1,unavailable_reason=NULL WHERE code=? AND lifecycle='REGISTERED' AND readiness='NOT_READY' AND grantable=0", code) != 1)
                    throw rejected("Permission state changed");
            }
            for (String role : List.of("ROOT", "LEARNING_MANAGER")) {
                if (jdbc.update("UPDATE rbac_role SET lifecycle='ENABLED',readiness='READY',grantable=1,unavailable_reason=NULL WHERE code=? AND lifecycle='REGISTERED' AND readiness='NOT_READY' AND grantable=0", role) != 1)
                    throw rejected("Role state changed");
            }
            var now = mapper.now();
            if (offline.switchSource(plan.rootUserId(), before.subject().getSource(), plan.target().expectedAuthorizationVersion(), UUID.randomUUID().toString()) != 1)
                throw rejected("Authority changed");
            for (State ordinary : plan.ordinaryUsers()) {
                Subject subject = ordinary.subject();
                if (offline.switchSource(subject.getUserId(), subject.getSource(), subject.getVersion(), UUID.randomUUID().toString()) != 1)
                    throw rejected("Ordinary account Authority changed");
            }
            String effectId = UUID.randomUUID().toString();
            Assignment assignment = new Assignment();
            assignment.setId(UUID.randomUUID().toString());
            assignment.setUserId(plan.rootUserId());
            assignment.setRoleCode("ROOT");
            assignment.setNamespace("AUTHORIZATION");
            assignment.setScopeType("GLOBAL");
            assignment.setGrantedAt(now);
            assignment.setVersion(UUID.randomUUID().toString());
            assignment.setGrantChangeId(effectId);
            if (mapper.insertAssignment(assignment) != 1) throw rejected("Assignment insert failed");
            Effect effect = new Effect(effectId, plan.rootUserId(), assignment.getId(), before, state(plan.rootUserId()));
            List<Effect> effects = new ArrayList<>();
            effects.add(effect);
            for (State ordinary : plan.ordinaryUsers())
                effects.add(new Effect(UUID.randomUUID().toString(), ordinary.subject().getUserId(), null, ordinary, state(ordinary.subject().getUserId())));
            effects.sort(Comparator.comparingLong(Effect::userId));
            Result result = new Result(2, plan.operationId(), plan.environmentId(), Kind.BOOTSTRAP, now, List.copyOf(effects));
            var operation = new StoredOperation();
            operation.setId(plan.operationId());
            operation.setEnvironmentId(plan.environmentId());
            operation.setKind("BOOTSTRAP");
            operation.setExecutorRef(plan.executorRef());
            operation.setApprovalRef(plan.approvalRef());
            operation.setApprovalSha256(plan.approvalSha256());
            operation.setRequestFingerprint(fingerprint(plan));
            operation.setRequestJson(encode(plan));
            operation.setApprovalsJson(approvals(plan));
            operation.setSnapshotVersion(2);
            operation.setReceipt(encode(result));
            operation.setOccurredAt(now);
            if (offline.insertOperation(operation) != 1) throw rejected("Migration audit failed");
            for (Effect item : effects)
                if (offline.insertEffect(plan.operationId(), item, encode(item.before()), encode(item.after())) != 1)
                    throw rejected("Migration effect failed");
            verifyRoot(plan.rootUserId());
            return result;
        });
    }

    private void validateIdentity(Plan p) {
        if (p == null || p.version() != 2 || p.target() == null || p.before() == null || p.before().subject() == null
                || p.rootUserId() <= 0 || p.rootUserId() != p.target().userId() || p.rootUserId() != p.before().subject().getUserId()
                || !url.equals(p.jdbcUrl()) || !serverUuid().equals(p.serverUuid()) || !database().equals(p.database())
                || !UUID.fromString(p.operationId()).toString().equals(p.operationId()) || !catalog.digest().equals(p.catalogDigest())
                || p.approvalSha256() == null || !p.approvalSha256().matches("[a-f0-9]{64}"))
            throw rejected("Approved plan target mismatch");
    }

    private void validateNew(Plan p) {
        validateIdentity(p);
        if (!offline.serverVersion().matches("8[.]4[.].*")) throw rejected("MySQL 8.4 required");
        // Explicit v2 migration must not run against an old V43-only operation table.
        String check = jdbc.queryForObject("SELECT CHECK_CLAUSE FROM information_schema.check_constraints WHERE constraint_schema=DATABASE() AND constraint_name='ck_rbac_offline_version'", String.class);
        if (check == null || !check.contains("2")) throw rejected("V45 required");
        var coverage = mapper.coverage();
        if (coverage.missing() != 0 || coverage.corrupt() != 0) throw rejected("Authority coverage incomplete");
        if (!Instant.parse(p.approvedUntil()).isAfter(mapper.now().toInstant(ZoneOffset.UTC)))
            throw rejected("Plan expired");
        List<Long> planned = targets(p).stream().map(s -> s.subject().getUserId()).sorted().toList();
        if (new HashSet<>(planned).size() != planned.size() || !planned.equals(jdbc.queryForList("SELECT user_id FROM rbac_authorization_authority ORDER BY user_id", Long.class)))
            throw rejected("Explicit account cohort changed; inspect again");
        for (State original : p.ordinaryUsers()) {
            State current = state(original.subject().getUserId());
            if (!Set.of("LEGACY","ASSIGNMENT").contains(current.subject().getSource()) || !current.assignments().isEmpty() || !encode(current).equals(encode(original)))
                throw rejected("Ordinary account or Authority changed; no existing grant may be overwritten");
        }
        if (!catalogFingerprint().equals(p.catalogBeforeFingerprint())) throw rejected("Catalog state changed");
        if (offline.rootHistory() != 0 || jdbc.queryForObject("SELECT COUNT(*) FROM rbac_offline_operation WHERE kind IN ('BOOTSTRAP','RECOVERY')", Long.class) != 0)
            throw rejected("First ROOT migration only; history must be empty");
        State current = state(p.rootUserId());
        Subject s = current.subject();
        if (!Set.of("LEGACY","ASSIGNMENT").contains(s.getSource()) || !Integer.valueOf(1).equals(s.getStatus()) || s.getDeletedAt() != null
                || !Set.of(1, 2, 3).contains(s.getLegacyRole()) || !current.assignments().isEmpty()
                || !s.getVersion().equals(p.target().expectedAuthorizationVersion())
                || !fingerprint(s).equals(p.target().expectedSubjectFingerprint()) || !encode(current).equals(encode(p.before())))
            throw rejected("Normal unchanged explicit Authority without assignments required");
        var ps = RbacAuthorizationReader.index(mapper.permissions());
        var rs = RbacAuthorizationReader.index(mapper.roles());
        for (String role : List.of("ROOT", "LEARNING_MANAGER")) {
            var row = rs.get(role);
            if (row == null || !"REGISTERED".equals(row.getLifecycle()) || !"NOT_READY".equals(row.getReadiness()) || row.isGrantable())
                throw rejected("Only untouched registered role definitions may be activated");
            if (!RbacCatalog.LEARNING_TASK_INTEGRATION_SUPPORT.containsAll(catalog.role(role).permissions()))
                throw rejected("Incomplete support artifact");
        }
        for (String code : RbacCatalog.LEARNING_TASK_INTEGRATION_SUPPORT) {
            var row = ps.get(code);
            if (row == null || !"REGISTERED".equals(row.getLifecycle()) || !"NOT_READY".equals(row.getReadiness()) || row.isGrantable())
                throw rejected("Only untouched registered permissions may be activated");
        }
    }

    private List<State> targets(Plan p) {
        List<State> states = new ArrayList<>(p.ordinaryUsers());
        states.add(p.before());
        return states;
    }

    private String catalogFingerprint() {
        var ps = mapper.permissions();
        var rs = mapper.roles();
        catalog.verify(ps, rs, mapper.members());
        return fingerprint(List.of(ps, rs, mapper.members()));
    }

    State state(long id) {
        Subject s = mapper.subject(id);
        if (s == null || s.getSource() == null || s.getVersion() == null || s.getVersion().isBlank())
            throw rejected("Existing User and explicit Authority required");
        return new State(s, List.copyOf(mapper.assignments(id)));
    }

    String serverUuid() {
        return jdbc.queryForObject("SELECT @@server_uuid", String.class);
    }

    String database() {
        return jdbc.queryForObject("SELECT DATABASE()", String.class);
    }

    private void verifyRoot(long id) {
        var decision = new RbacAuthorizationReader(mapper, catalog, new AdminPermissionPolicy()).readCurrent(id, mapper.now());
        if (!decision.allows("authorization:root-manage", "AUTHORIZATION") || !decision.allows("authorization:assignment-manage", "AUTHORIZATION"))
            throw rejected("Post-migration ROOT decision failed");
    }

    private Result restore(Plan p, StoredOperation stored) {
        try {
            if (!Integer.valueOf(2).equals(stored.getSnapshotVersion()) || !fingerprint(p).equals(stored.getRequestFingerprint())
                    || !p.equals(RbacJson.mapper().readValue(stored.getRequestJson(), Plan.class))
                    || !p.environmentId().equals(stored.getEnvironmentId()) || !p.executorRef().equals(stored.getExecutorRef())
                    || !p.approvalRef().equals(stored.getApprovalRef()) || !p.approvalSha256().equals(stored.getApprovalSha256())
                    || !RbacJson.mapper().readTree(approvals(p)).equals(RbacJson.mapper().readTree(stored.getApprovalsJson())))
                throw rejected("Operation semantic or approval conflict");
            Result result = RbacJson.mapper().readValue(stored.getReceipt(), Result.class);
            var facts = offline.effects(p.operationId());
            if (result.version() != 2 || !p.operationId().equals(result.operationId()) || !p.environmentId().equals(result.environmentId())
                    || result.kind() != Kind.BOOTSTRAP || !Objects.equals(result.committedAt(), stored.getOccurredAt())
                    || result.effects().size() != targets(p).size() || facts.size() != targets(p).size())
                throw rejected("Corrupt migration receipt");
            Map<Long, State> original = new HashMap<>();
            targets(p).forEach(s -> original.put(s.subject().getUserId(), s));
            Set<Long> seen = new HashSet<>();
            for (int i = 0; i < facts.size(); i++) {
                Effect e = result.effects().get(i);
                var f = facts.get(i);
                if (!seen.add(e.userId()) || e.userId() != f.userId() || !e.id().equals(f.id()) || !Objects.equals(e.assignmentId(), f.assignmentId())
                        || !Objects.equals(original.get(e.userId()), e.before()) || !e.before().equals(RbacJson.mapper().readValue(f.beforeSnapshot(), State.class))
                        || !e.after().equals(RbacJson.mapper().readValue(f.afterSnapshot(), State.class)))
                    throw rejected("Corrupt migration effect");
            }
            RbacOfflineAudit.verify(mapper, mapper.assignments(p.rootUserId()));
            return result; // Historical receipt only; never re-grants even after later revocation.
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("Corrupt migration receipt", ex);
        }
    }

    private static IllegalStateException rejected(String message) {
        return new IllegalStateException(message);
    }

    private String approvals(Plan p) {
        return encode(List.of(Map.of("method", "OPERATOR_MANIFEST_SHA256", "executorRef", p.executorRef(),
                "approvalRef", p.approvalRef(), "approvalSha256", p.approvalSha256(), "planFingerprint", fingerprint(p))));
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2)
            throw new IllegalArgumentException("Usage: <jdbc-url> inspect <environment> <root-id> <executor> <approval-ref> <approval-file>; or <url> <plan.json> [--execute <exact-plan-sha256>]");
        String user = Objects.requireNonNull(System.getenv("RBAC_INITIAL_DB_USER"), "RBAC_INITIAL_DB_USER required");
        String password = Objects.requireNonNull(System.getenv("RBAC_INITIAL_DB_PASSWORD"), "RBAC_INITIAL_DB_PASSWORD required");
        var ds = new DriverManagerDataSource(args[0] + "?sslMode=DISABLED&allowPublicKeyRetrieval=true&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true", user, password);
        var tool = new RbacInitialMigration(args[0], ds);
        if ("inspect".equals(args[1])) {
            if (args.length != 7) throw new IllegalArgumentException("Explicit inspect arguments required");
            System.out.println(encode(tool.inspect(args[2], Long.parseLong(args[3]), args[4], args[5], RbacCatalog.hash(Files.readAllBytes(Path.of(args[6]))))));
        } else {
            byte[] bytes = Files.readAllBytes(Path.of(args[1]));
            Plan plan = RbacJson.mapper().readValue(bytes, Plan.class);
            if (args.length == 2) {
                tool.dryRun(plan);
                System.out.println("DRY_RUN_PASS planSha256=" + RbacCatalog.hash(bytes));
            } else if (args.length == 4 && "--execute".equals(args[2]) && RbacCatalog.hash(bytes).equals(args[3]))
                System.out.println(encode(tool.execute(plan)));
            else throw new IllegalArgumentException("Exact approved plan SHA-256 and explicit --execute required");
        }
    }
}
