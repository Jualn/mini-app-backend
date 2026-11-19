package cn.jualn.miniapp.module.authorization.service;

import cn.jualn.miniapp.module.authorization.mapper.RbacMapper;

import cn.jualn.miniapp.module.admin.auth.support.AdminPermissionPolicy;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

import static cn.jualn.miniapp.module.authorization.model.RbacModels.*;

/**
 * Explicit standalone synthetic executor. No component, HTTP, application runner or production mode.
 */
public final class RbacOfflineTool {
    static final Set<String> SYNTHETIC_SUPPORT = Set.of("audit-log:read", "authorization:read",
            "authorization:assignment-manage", "authorization:root-manage", "notice:read", "system:read");
    private static final ObjectMapper JSON = RbacJson.mapper();

    public enum Kind {BOOTSTRAP, SOURCE_SWITCH, RECOVERY}

    public record Target(long userId, String expectedAuthorizationVersion, String expectedSubjectFingerprint) {
    }

    public record Request(int version, String operationId, String environmentId, Kind kind, String executorRef,
                          String approvalRef, String approvalSha256, String approvedUntil,
                          String expectedCatalogVersion,
                          Long sponsorUserId, String expectedSponsorVersion, List<Target> targets) {
        public Request {
            targets = targets == null ? List.of() : List.copyOf(targets);
        }
    }

    public record Approval(String approverRef, String publicKey, String signature) {
    }

    public record SignedRequest(Request request, List<Approval> approvals) {
        public SignedRequest {
            approvals = approvals == null ? List.of() : List.copyOf(approvals);
        }
    }

    public record Environment(String environmentId, String executorRef, String approverOne, String keyOneSha256,
                              String approverTwo, String keyTwoSha256) {
    }

    public record State(Subject subject, List<Assignment> assignments) {
    }

    public record Effect(String id, long userId, String assignmentId, State before, State after) {
    }

    public record Result(int version, String operationId, String environmentId, Kind kind, LocalDateTime committedAt,
                         List<Effect> effects) {
    }

    public record Inspection(String environmentId, String catalogVersion, List<Target> targets, List<State> current) {
    }

    public record Preview(String operationId, Kind kind, List<State> before, String intendedEffect) {
    }

    public record StoredEffect(String id, long userId, String assignmentId, String beforeSnapshot,
                               String afterSnapshot) {
    }

    @Data
    public static class StoredOperation {
        private String id, environmentId, kind, executorRef, approvalRef, approvalSha256;
        private String requestFingerprint, requestJson, approvalsJson, receipt;
        private Integer snapshotVersion;
        private LocalDateTime occurredAt;
    }

    private final RbacMapper mapper;
    private final RbacOfflineMapper offline;
    private final RbacCatalog catalog;
    private final RbacAuthorizationReader reader;
    private final TransactionTemplate writes;
    private final TransactionTemplate reads;

    RbacOfflineTool(String url, RbacMapper mapper, RbacOfflineMapper offline, RbacCatalog catalog,
                    DataSourceTransactionManager manager) {
        requireSyntheticUrl(url);
        this.mapper = mapper;
        this.offline = offline;
        this.catalog = catalog;
        this.reader = new RbacAuthorizationReader(mapper, catalog, new AdminPermissionPolicy());
        writes = new TransactionTemplate(manager);
        writes.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        writes.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        writes.setTimeout(30);
        reads = new TransactionTemplate(manager);
        reads.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        reads.setReadOnly(true);
        reads.setTimeout(30);
    }

    static void requireSyntheticUrl(String url) {
        if (url == null || !url.matches("jdbc:mysql://127[.]0[.]0[.]1:[0-9]{1,5}/rbac_offline_test"))
            throw new IllegalArgumentException("Production execution is disabled; dedicated loopback rbac_offline_test required");
    }

    private Environment environment() {
        if (!offline.serverVersion().matches("8[.]4[.].*")) throw rejected("MySQL 8.4 required");
        Environment env = offline.environment();
        if (env == null || env.environmentId() == null || !env.environmentId().startsWith("synthetic-")
                || env.approverOne().equals(env.approverTwo()) || env.keyOneSha256().equals(env.keyTwoSha256()))
            throw rejected("Independent synthetic environment guard required");
        return env;
    }

    public Inspection inspect(List<Long> users) {
        if (users == null || users.isEmpty() || users.size() > 2 || users.stream().anyMatch(u -> u == null || u <= 0))
            throw rejected("One or two explicit users required");
        return reads.execute(tx -> {
            Environment env = environment();
            var states = users.stream().distinct().sorted().map(this::state).toList();
            return new Inspection(env.environmentId(), catalogVersion(), states.stream().map(s -> new Target(
                    s.subject().getUserId(), s.subject().getVersion(), fingerprint(s.subject()))).toList(), states);
        });
    }

    /**
     * Default entry: validation and snapshot only; no row locks or successful fact writes.
     */
    public Preview dryRun(Request input) {
        Request request = normalize(input);
        return reads.execute(tx -> {
            validateEnvironment(request);
            validateNew(request);
            return new Preview(request.operationId(), request.kind(), request.targets().stream().map(t -> state(t.userId())).toList(),
                    request.kind() == Kind.SOURCE_SWITCH ? "ASSIGNMENT source only; no grants" : "One new ROOT/AUTHORIZATION/GLOBAL assignment; no expiry");
        });
    }

    public Result execute(SignedRequest signed) {
        Objects.requireNonNull(signed, "signed request");
        Request request = normalize(signed.request());
        return writes.execute(tx -> {
            if (!"ROOT".equals(mapper.lockGovernance())) throw rejected("ROOT mutex missing");
            Environment env = validateEnvironment(request);
            verifyApprovals(request, signed.approvals(), env);
            TreeSet<Long> locks = new TreeSet<>();
            request.targets().forEach(t -> locks.add(t.userId()));
            if (request.sponsorUserId() != null) locks.add(request.sponsorUserId());
            locks.forEach(mapper::lockSubject);
            StoredOperation prior = offline.operation(request.operationId());
            if (prior != null) return restore(request, prior);
            validateNew(request);
            LocalDateTime now = mapper.now();
            List<Effect> effects = new ArrayList<>();
            for (Target target : request.targets()) {
                State before = state(target.userId());
                String effectId = UUID.randomUUID().toString();
                String nextVersion = UUID.randomUUID().toString();
                if (offline.switchSource(target.userId(), before.subject().getSource(), target.expectedAuthorizationVersion(), nextVersion) != 1)
                    throw rejected("Authority version conflict");
                String assignmentId = null;
                if (request.kind() != Kind.SOURCE_SWITCH) {
                    Assignment assignment = new Assignment();
                    assignmentId = UUID.randomUUID().toString();
                    assignment.setId(assignmentId);
                    assignment.setUserId(target.userId());
                    assignment.setRoleCode("ROOT");
                    assignment.setNamespace("AUTHORIZATION");
                    assignment.setScopeType("GLOBAL");
                    assignment.setGrantedAt(now);
                    assignment.setVersion(UUID.randomUUID().toString());
                    assignment.setGrantChangeId(effectId);
                    if (mapper.insertAssignment(assignment) != 1) throw rejected("Assignment write failed");
                }
                effects.add(new Effect(effectId, target.userId(), assignmentId, before, state(target.userId())));
            }
            Result result = new Result(1, request.operationId(), request.environmentId(), request.kind(), now, List.copyOf(effects));
            var stored = new StoredOperation();
            stored.setId(request.operationId());
            stored.setEnvironmentId(request.environmentId());
            stored.setKind(request.kind().name());
            stored.setExecutorRef(request.executorRef());
            stored.setApprovalRef(request.approvalRef());
            stored.setApprovalSha256(request.approvalSha256());
            stored.setRequestFingerprint(fingerprint(request));
            stored.setRequestJson(encode(request));
            stored.setApprovalsJson(encode(signed.approvals()));
            stored.setSnapshotVersion(1);
            stored.setReceipt(encode(result));
            stored.setOccurredAt(now);
            if (offline.insertOperation(stored) != 1) throw rejected("Operation fact write failed");
            for (Effect effect : effects) {
                if (offline.insertEffect(request.operationId(), effect, encode(effect.before()), encode(effect.after())) != 1)
                    throw rejected("Effect fact write failed");
            }
            return result;
        });
    }

    private Environment validateEnvironment(Request r) {
        Environment env = environment();
        if (!env.environmentId().equals(r.environmentId()) || !env.executorRef().equals(r.executorRef()))
            throw rejected("Environment or executor mismatch");
        return env;
    }

    private void validateNew(Request r) {
        var coverage = mapper.coverage();
        if (coverage.missing() != 0 || coverage.corrupt() != 0) throw rejected("Authority coverage incomplete");
        if (!Instant.parse(r.approvedUntil()).isAfter(mapper.now().toInstant(ZoneOffset.UTC)))
            throw rejected("Approval expired");
        if (!catalogVersion().equals(r.expectedCatalogVersion())) throw rejected("Catalog version conflict");
        requireRole("ROOT");
        long roots = mapper.rootUsers().stream().filter(u -> reader.readCurrent(u, mapper.now()).grants().stream()
                .anyMatch(g -> "ROOT".equals(g.roleCode()) && g.allows("authorization:root-manage", "AUTHORIZATION"))).count();
        if (r.kind() == Kind.SOURCE_SWITCH) {
            Subject sponsor = mapper.subject(r.sponsorUserId());
            if (sponsor == null || !Objects.equals(sponsor.getVersion(), r.expectedSponsorVersion()))
                throw rejected("Sponsor version conflict");
            Decision d = reader.readCurrent(r.sponsorUserId(), mapper.now());
            if (!d.allows("authorization:root-manage", "AUTHORIZATION") || !d.allows("authorization:assignment-manage", "AUTHORIZATION"))
                throw rejected("Current ROOT sponsor required");
        } else {
            if (roots != 0 || !mapper.rootUsers().isEmpty()) throw rejected("Zero current ROOT required");
            if ((r.kind() == Kind.BOOTSTRAP) != (offline.rootHistory() == 0))
                throw rejected("Bootstrap/recovery history conflict");
        }
        for (Target target : r.targets()) {
            State current = state(target.userId());
            Subject s = current.subject();
            if (s.getDeletedAt() != null || !Integer.valueOf(1).equals(s.getStatus()))
                throw rejected("Current normal account required");
            if (s.getLegacyRole() == null || !Set.of(1, 2, 3).contains(s.getLegacyRole()))
                throw rejected("Invalid historical user role");
            if (!Objects.equals(s.getVersion(), target.expectedAuthorizationVersion()) || !fingerprint(s).equals(target.expectedSubjectFingerprint()))
                throw rejected("Subject or Authority version conflict");
            if (!Set.of("LEGACY", "ASSIGNMENT").contains(s.getSource())) throw rejected("Invalid Authority");
            if (r.kind() == Kind.SOURCE_SWITCH && !"LEGACY".equals(s.getSource())) throw rejected("LEGACY target required");
            if ("LEGACY".equals(s.getSource()) && !current.assignments().isEmpty())
                throw rejected("Shadow Assignment rejected");
            if ("ASSIGNMENT".equals(s.getSource()) && current.assignments().stream().anyMatch(a -> a.getRevokedAt() == null))
                throw rejected("Recovery cannot reactivate existing grants");
        }
    }

    private String catalogVersion() {
        var ps = mapper.permissions();
        var rs = mapper.roles();
        catalog.verify(ps, rs, mapper.members());
        return catalog.runtimeVersion(ps, rs);
    }

    private void requireRole(String code) {
        var ps = RbacAuthorizationReader.index(mapper.permissions());
        var rs = RbacAuthorizationReader.index(mapper.roles());
        if (!catalog.roleReady(code, rs, ps) || !rs.get(code).isGrantable()
                || catalog.role(code).permissions().stream().anyMatch(p -> !ps.get(p).isGrantable()))
            throw rejected("Complete role support/readiness/grantability required");
    }

    private State state(long user) {
        Subject s = mapper.subject(user);
        if (s == null || s.getSource() == null || s.getVersion() == null || s.getVersion().isBlank())
            throw rejected("Explicit Authority required");
        return new State(s, List.copyOf(mapper.assignments(user)));
    }

    private Result restore(Request request, StoredOperation prior) {
        if (!Integer.valueOf(1).equals(prior.getSnapshotVersion()) || !fingerprint(request).equals(prior.getRequestFingerprint())
                || !encode(request).equals(encode(decode(prior.getRequestJson(), Request.class))))
            throw rejected("Operation ID semantic conflict");
        Result result = decode(prior.getReceipt(), Result.class);
        if (result.version() != 1 || !request.operationId().equals(result.operationId()) || request.kind() != result.kind()
                || !request.environmentId().equals(result.environmentId()) || result.committedAt() == null
                || result.effects().size() != request.targets().size()) throw rejected("Corrupt operation receipt");
        var facts = offline.effects(request.operationId());
        if (facts.size() != result.effects().size()) throw rejected("Incomplete operation effects");
        for (int i = 0; i < facts.size(); i++) {
            StoredEffect stored = facts.get(i);
            Effect effect = result.effects().get(i);
            if (!stored.id().equals(effect.id()) || stored.userId() != effect.userId()
                    || effect.userId() != request.targets().get(i).userId() || !Objects.equals(stored.assignmentId(), effect.assignmentId())
                    || !encode(decode(stored.beforeSnapshot(), State.class)).equals(encode(effect.before()))
                    || !encode(decode(stored.afterSnapshot(), State.class)).equals(encode(effect.after())))
                throw rejected("Corrupt operation effect");
            if (effect.assignmentId() != null && effect.after().assignments().stream().noneMatch(a ->
                    effect.assignmentId().equals(a.getId()) && effect.id().equals(a.getGrantChangeId())))
                throw rejected("Missing grant audit reference");
        }
        return result;
    }

    static Request normalize(Request r) {
        Objects.requireNonNull(r, "request");
        if (r.version() != 1 || r.kind() == null || r.operationId() == null || !UUID.fromString(r.operationId()).toString().equals(r.operationId())
                || !text(r.environmentId(), 96) || !text(r.executorRef(), 128) || !text(r.approvalRef(), 500)
                || r.approvalSha256() == null || !r.approvalSha256().matches("[a-f0-9]{64}")
                || r.expectedCatalogVersion() == null || !r.expectedCatalogVersion().matches("[a-f0-9]{64}"))
            throw rejected("Invalid explicit command");
        Instant.parse(r.approvedUntil());
        if (r.targets().size() != 1
                || r.targets().stream().map(Target::userId).distinct().count() != r.targets().size())
            throw rejected("Explicit distinct targets required");
        for (Target t : r.targets())
            if (t.userId() <= 0 || !text(t.expectedAuthorizationVersion(), 36)
                    || t.expectedSubjectFingerprint() == null || !t.expectedSubjectFingerprint().matches("[a-f0-9]{64}"))
                throw rejected("Target preconditions required");
        if (r.kind() == Kind.SOURCE_SWITCH) {
            if (r.sponsorUserId() == null || r.sponsorUserId() <= 0 || !text(r.expectedSponsorVersion(), 36)
                    || r.targets().stream().anyMatch(t -> t.userId() == r.sponsorUserId()))
                throw rejected("Independent ROOT sponsor required");
        } else if (r.sponsorUserId() != null || r.expectedSponsorVersion() != null)
            throw rejected("No fictitious ROOT sponsor allowed");
        return new Request(r.version(), r.operationId(), r.environmentId(), r.kind(), r.executorRef(), r.approvalRef(), r.approvalSha256(),
                r.approvedUntil(), r.expectedCatalogVersion(), r.sponsorUserId(), r.expectedSponsorVersion(), r.targets().stream().sorted(Comparator.comparingLong(Target::userId)).toList());
    }

    private static boolean text(String value, int max) {
        return value != null && !value.isBlank() && value.codePointCount(0, value.length()) <= max;
    }

    static byte[] signingBytes(Request request) {
        try {
            return JSON.writeValueAsBytes(normalize(request));
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot encode command", e);
        }
    }

    private void verifyApprovals(Request r, List<Approval> approvals, Environment env) {
        if (approvals.size() != 2) throw rejected("Two independent approvals required");
        Map<String, String> trusted = Map.of(env.approverOne(), env.keyOneSha256(), env.approverTwo(), env.keyTwoSha256());
        Set<String> seen = new HashSet<>();
        for (Approval a : approvals) {
            try {
                byte[] key = Base64.getDecoder().decode(a.publicKey());
                if (!seen.add(a.approverRef()) || !Objects.equals(trusted.get(a.approverRef()), RbacCatalog.hash(key)))
                    throw rejected("Untrusted approval");
                Signature verifier = Signature.getInstance("Ed25519");
                verifier.initVerify(KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(key)));
                verifier.update(signingBytes(r));
                if (!verifier.verify(Base64.getDecoder().decode(a.signature())))
                    throw rejected("Approval signature mismatch");
            } catch (java.security.GeneralSecurityException e) {
                throw new IllegalArgumentException("Approval verification failed", e);
            }
        }
    }

    static String encode(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("Cannot encode offline fact", e);
        }
    }

    private static <T> T decode(String value, Class<T> type) {
        try {
            return JSON.readValue(value, type);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Corrupt offline fact", e);
        }
    }

    static String fingerprint(Object value) {
        return RbacCatalog.hash(encode(value).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static IllegalStateException rejected(String message) {
        return new IllegalStateException(message);
    }

    static RbacOfflineTool connect(String url, String user, String password) throws Exception {
        requireSyntheticUrl(url); // before creating any connection; never reads application configuration
        var ds = new DriverManagerDataSource(url + "?sslMode=DISABLED&allowPublicKeyRetrieval=true&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true", user, password);
        return create(url, ds, new DataSourceTransactionManager(ds), new RbacCatalog(SYNTHETIC_SUPPORT));
    }

    static RbacOfflineTool create(String url, DataSource ds, DataSourceTransactionManager manager, RbacCatalog catalog) throws Exception {
        requireSyntheticUrl(url);
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.setCacheEnabled(false);
        config.addMapper(RbacMapper.class);
        config.addMapper(RbacOfflineMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(ds);
        factory.setConfiguration(config);
        factory.setMapperLocations(new ClassPathResource("mapper/RbacMapper.xml"), new ClassPathResource("mapper/RbacOfflineMapper.xml"));
        var session = new SqlSessionTemplate(Objects.requireNonNull(factory.getObject()));
        return new RbacOfflineTool(url, session.getMapper(RbacMapper.class), session.getMapper(RbacOfflineMapper.class), catalog, manager);
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2 || args.length > 3)
            throw new IllegalArgumentException("Usage: <synthetic-jdbc-url> <signed-request.json> [--execute]; or <url> inspect <userId[,userId]>");
        String url = args[0];
        requireSyntheticUrl(url);
        String user = Objects.requireNonNull(System.getenv("RBAC_OFFLINE_DB_USER"), "Explicit RBAC_OFFLINE_DB_USER required");
        String password = Objects.requireNonNull(System.getenv("RBAC_OFFLINE_DB_PASSWORD"), "Explicit RBAC_OFFLINE_DB_PASSWORD required");
        var tool = connect(url, user, password);
        if ("inspect".equals(args[1]) && args.length == 3) {
            System.out.println(encode(tool.inspect(Arrays.stream(args[2].split(",")).map(Long::parseLong).toList())));
            return;
        }
        if (args.length == 3 && !"--execute".equals(args[2])) throw new IllegalArgumentException("Unknown flag");
        var signed = JSON.readValue(Files.readString(Path.of(args[1])), SignedRequest.class);
        System.out.println(encode(args.length == 3 ? tool.execute(signed) : tool.dryRun(signed.request())));
    }
}
