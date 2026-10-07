package cn.jualn.miniapp.module.admin.auth;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.config.SaTokenConfig;
import cn.dev33.satoken.context.SaHolder;
import cn.dev33.satoken.context.mock.*;
import cn.dev33.satoken.dao.SaTokenDaoForRedisTemplate;
import cn.jualn.miniapp.common.constant.RedisKeyConstant;
import cn.jualn.miniapp.common.enums.*;
import cn.jualn.miniapp.common.exception.*;
import cn.jualn.miniapp.common.security.AdminStpUtil;
import cn.jualn.miniapp.infrastructure.cache.*;
import cn.jualn.miniapp.module.admin.auth.bo.qrlogin.*;
import cn.jualn.miniapp.module.admin.auth.config.*;
import cn.jualn.miniapp.module.admin.auth.service.*;
import cn.jualn.miniapp.module.admin.auth.service.impl.AdminQrLoginServiceImpl;
import cn.jualn.miniapp.module.admin.auth.support.*;
import cn.jualn.miniapp.module.user.bo.UserProfileBO;
import cn.jualn.miniapp.module.user.service.UserService;
import cn.jualn.miniapp.third.wx.client.WxClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import static cn.jualn.miniapp.module.admin.auth.enums.AdminQrLoginV2Status.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Requires an explicitly supplied disposable loopback Redis port; never uses application configuration. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdminQrLoginRedisTest {
    private LettuceConnectionFactory factory;
    private StringRedisTemplate redis;
    private AdminQrLoginStore store;
    private AdminTokenService tokens;
    private UserService users;
    private AdminQrLoginService service;
    private final AtomicLong subjects = new AtomicLong(8000000000L);
    private cn.dev33.satoken.dao.SaTokenDao oldDao;
    private SaTokenConfig oldConfig;
    private cn.dev33.satoken.json.SaJsonTemplate oldJson;
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @BeforeAll void setup() {
        String supplied = System.getProperty("admin.qr.test.port");
        Assumptions.assumeTrue(supplied != null, "Disposable Redis port required");
        int port = Integer.parseInt(supplied);
        assertTrue(port >= 1024 && port <= 65535);
        factory = new LettuceConnectionFactory("127.0.0.1", port);
        factory.afterPropertiesSet(); factory.start();
        redis = new StringRedisTemplate(factory);
        oldDao = SaManager.getSaTokenDao(); oldConfig = SaManager.getConfig();
        oldJson = SaManager.getSaJsonTemplate();
        SaManager.setSaJsonTemplate(new cn.dev33.satoken.json.SaJsonTemplateForJackson());
        SaTokenDaoForRedisTemplate dao = new SaTokenDaoForRedisTemplate(); dao.init(factory);
        SaManager.setSaTokenDao(dao);
        SaManager.setConfig(new SaTokenConfig().setTokenName("Authorization").setTokenPrefix("Bearer")
                .setJwtSecretKey("synthetic-isolated-admin-qr-test-secret-1234567890")
                .setIsConcurrent(true).setIsShare(false));
        store = new AdminQrLoginStore(redis, registry);
        users = mock(UserService.class);
        when(users.getUserProfile(anyLong())).thenAnswer(inv -> profile(inv.getArgument(0)));
        tokens = new AdminTokenService(users, new AdminPermissionPolicy(), new AdminAuthStore(redis),
                new AdminAuthProperties(), store, new ObjectMapper().findAndRegisterModules());
        service = service(tokens);
    }
    private AdminQrLoginService service(AdminTokenService tokenService) {
        return new AdminQrLoginServiceImpl(store, tokenService, users, new AdminPermissionPolicy(),
                new AdminQrLoginProperties(), mock(WxClient.class), registry);
    }
    @AfterAll void teardown() {
        if (oldDao != null) SaManager.setSaTokenDao(oldDao);
        if (oldConfig != null) SaManager.setConfig(oldConfig);
        if (oldJson != null) SaManager.setSaJsonTemplate(oldJson);
        if (factory != null) factory.destroy();
        registry.close();
    }
    @AfterEach void context() { SaTokenContextMockUtil.clearContext(); }
    private UserProfileBO profile(long id) {
        return UserProfileBO.builder().id(id).nickname("Synthetic operator").role(UserRole.ADMIN).status(UserStatus.NORMAL).build();
    }
    private record Fixture(String id, String scene, String secret, String sceneHash, String secretHash) {}
    private Fixture pending() {
        String id = AdminAuthCrypto.randomUrlToken(16), scene = AdminAuthCrypto.randomUrlToken(24), secret = AdminAuthCrypto.randomUrlToken(32);
        Fixture f = new Fixture(id, scene, secret, AdminAuthCrypto.sha256(scene), AdminAuthCrypto.sha256(secret));
        assertNotNull(store.publish(id, f.sceneHash(), f.secretHash(), new byte[]{(byte)137,80,78,71,13,10,26,10},
                Duration.ofMinutes(2), Duration.ofMinutes(5), 1500));
        return f;
    }
    private AdminQrLoginStore.Snapshot web(Fixture f, String op) { return store.web(op, f.id(), f.secretHash(), null, null); }
    private Fixture confirmed(long subject) {
        Fixture f = pending(); store.mobile("scan", f.sceneHash(), subject); store.mobile("confirm", f.sceneHash(), subject); return f;
    }
    private void expire(Fixture f) {
        redis.opsForHash().putAll(RedisKeyConstant.adminQrSession(f.id()), Map.of("createdAt", "0", "expiresAt", "1"));
    }
    private <T> List<T> concurrent(int n, java.util.function.IntFunction<T> action) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(n), go = new CountDownLatch(1);
        try {
            List<Future<T>> pending = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int index = i;
                pending.add(pool.submit(() -> { ready.countDown(); assertTrue(go.await(20, TimeUnit.SECONDS)); return action.apply(index); }));
            }
            assertTrue(ready.await(20, TimeUnit.SECONDS)); go.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : pending) results.add(future.get(45, TimeUnit.SECONDS));
            return results;
        } finally { pool.shutdownNow(); }
    }

    @Test void firstOf100AuthenticatedScansWinsAndOnlyWinnerCanConfirm() throws Exception {
        Fixture f = pending(); long base = subjects.addAndGet(1000);
        List<Long> results = concurrent(100, i -> {
            try { return store.mobile("scan", f.sceneHash(), base + i).userId(); }
            catch (ContractProblemException e) { assertEquals("/problems/qr-login-subject-conflict", e.getType()); return null; }
        });
        assertEquals(1, results.stream().filter(Objects::nonNull).distinct().count());
        long winner = results.stream().filter(Objects::nonNull).findFirst().orElseThrow();
        List<Boolean> confirmations = concurrent(100, i -> {
            try { return store.mobile("confirm", f.sceneHash(), base + i).userId() == winner; }
            catch (ContractProblemException e) { assertEquals("/problems/qr-login-subject-conflict", e.getType()); return false; }
        });
        assertEquals(1, confirmations.stream().filter(Boolean::booleanValue).count());
        double before = registry.get("jualn.admin.qr.login.transition").tags("from", "SCANNED", "to", "CONFIRMED").counter().count();
        concurrent(100, i -> store.mobile("confirm", f.sceneHash(), winner));
        assertEquals(before, registry.get("jualn.admin.qr.login.transition").tags("from", "SCANNED", "to", "CONFIRMED").counter().count());
        assertEquals(CONFIRMED, web(f, "read").session().status());
    }

    @Test void markerlessAdminTokenIsRejected() {
        long subject = subjects.incrementAndGet();
        String token = AdminStpUtil.STP_LOGIC.createLoginSession(subject);
        SaTokenContextMockUtil.setMockContext();
        ((SaRequestForMock) SaHolder.getRequest()).headerMap.put("Authorization", "Bearer " + token);
        assertThrows(BusinessException.class, tokens::requireValidLogin);
    }

    @Test void hundredConsumersReserveOneTokenAndCommitOneActivation() throws Exception {
        long subject = subjects.incrementAndGet(); Fixture f = confirmed(subject);
        Fixture earlier = confirmed(subject);
        String existing = service(tokens).consumeSession(earlier.id(), earlier.secret(), "synthetic-ip").token();
        List<AdminQrCandidateBO> candidates = concurrent(100, i -> {
            var prepared = tokens.prepareQrCandidate(subject, f.id(), profile(subject), web(f, "read").now());
            return store.web("reserve", f.id(), f.secretHash(), prepared, null).candidate();
        });
        assertEquals(1, candidates.stream().map(AdminQrCandidateBO::token).distinct().count());
        var saved = candidates.get(0);
        double before = registry.find("jualn.admin.qr.login.transition").tags("from", "CONFIRMED", "to", "CONSUMED").counter() == null
                ? 0 : registry.get("jualn.admin.qr.login.transition").tags("from", "CONFIRMED", "to", "CONSUMED").counter().count();
        List<AdminQrLoginStore.Snapshot> commits = concurrent(100, i -> {
            tokens.materializeQrCandidate(subject, saved, web(f, "read").now());
            return store.web("finalize", f.id(), f.secretHash(), saved, tokens.qrMappingKey(saved));
        });
        assertEquals(1, commits.stream().filter(s -> s.outcome().equals("COMMITTED")).count());
        assertEquals(before + 1, registry.get("jualn.admin.qr.login.transition").tags("from", "CONFIRMED", "to", "CONSUMED").counter().count());
        assertTrue(tokens.validQrResult(subject, f.id(), saved, web(f, "read").now()));
        assertEquals(Long.toString(subject), String.valueOf(AdminStpUtil.STP_LOGIC.getLoginIdByToken(existing)));
        assertTrue(redis.getExpire(RedisKeyConstant.adminQrActivation(f.id())) > 7 * 3600);
        assertTrue(redis.getExpire(RedisKeyConstant.adminQrSession(f.id())) < 7 * 60 + 1);
        assertEquals(CONSUMED, web(f, "read").session().status());
        tokens.discardQrCandidate(saved);
        assertEquals(Long.toString(subject), String.valueOf(AdminStpUtil.STP_LOGIC.getLoginIdByToken(existing)));
        SaTokenContextMockUtil.setMockContext();
        ((SaRequestForMock) SaHolder.getRequest()).headerMap.put("Authorization", "Bearer " + existing);
        assertEquals(Long.toString(subject), tokens.requireValidLogin().getId());
    }

    @Test void materializedButUncommittedCandidateIsRejectedThenNewServiceRecoversSameResult() {
        long subject = subjects.incrementAndGet(); Fixture f = confirmed(subject);
        var candidate = tokens.prepareQrCandidate(subject, f.id(), profile(subject), web(f, "read").now());
        store.web("reserve", f.id(), f.secretHash(), candidate, null);
        tokens.materializeQrCandidate(subject, candidate, web(f, "read").now());
        SaTokenContextMockUtil.setMockContext();
        ((SaRequestForMock) SaHolder.getRequest()).headerMap.put("Authorization", "Bearer " + candidate.token());
        assertThrows(BusinessException.class, tokens::requireValidLogin);
        SaTokenContextMockUtil.clearContext();
        var recovered = service(tokens).consumeSession(f.id(), f.secret(), "synthetic-recovery");
        assertEquals(candidate.token(), recovered.token());
        var replayed = service(tokens).consumeSession(f.id(), f.secret(), "synthetic-recovery");
        assertEquals(recovered, replayed);
        SaTokenContextMockUtil.setMockContext();
        ((SaRequestForMock) SaHolder.getRequest()).headerMap.put("Authorization", "Bearer " + candidate.token());
        assertEquals(Long.toString(subject), tokens.requireValidLogin().getId());
        tokens.logoutCurrent();
        SaTokenContextMockUtil.clearContext();
        // A late in-flight materialization cannot recreate activation or restore replay after logout.
        tokens.materializeQrCandidate(subject, candidate, web(f, "read").now());
        assertFalse(tokens.validQrResult(subject, f.id(), candidate, web(f, "read").now()));
        assertEquals("/problems/qr-login-session-already-consumed", assertThrows(ContractProblemException.class,
                () -> service.consumeSession(f.id(), f.secret(), "synthetic-recovery")).getType());
    }

    @Test void cancelAndExpiryBeatLateFinalizationWithoutActivatingCandidate() {
        for (boolean expiry : List.of(false, true)) {
            long subject = subjects.incrementAndGet(); Fixture f = confirmed(subject);
            var candidate = tokens.prepareQrCandidate(subject, f.id(), profile(subject), web(f, "read").now());
            store.web("reserve", f.id(), f.secretHash(), candidate, null);
            if (expiry) expire(f); else web(f, "cancel");
            tokens.materializeQrCandidate(subject, candidate, System.currentTimeMillis());
            assertThrows(ContractProblemException.class, () -> store.web("finalize", f.id(), f.secretHash(), candidate, tokens.qrMappingKey(candidate)));
            assertFalse(store.activated(f.id(), candidate.tokenHash(), subject));
            assertEquals(expiry ? EXPIRED : CANCELLED, web(f, "read").session().status());
        }
    }

    @Test void expiryRetainsStateAndSubjectConflictHasPrecedence() {
        Fixture f = pending(); long subject = subjects.incrementAndGet(); store.mobile("scan", f.sceneHash(), subject); expire(f);
        assertEquals("/problems/qr-login-subject-conflict", assertThrows(ContractProblemException.class,
                () -> store.mobile("scan", f.sceneHash(), subject + 1)).getType());
        assertEquals(EXPIRED, store.mobile("scan", f.sceneHash(), subject).session().status());
        assertEquals(EXPIRED, web(f, "read").session().status());
        assertTrue(redis.hasKey(RedisKeyConstant.adminQrSession(f.id())));
        assertEquals("/problems/qr-login-invalid-web-credential", assertThrows(ContractProblemException.class,
                () -> store.web("read", f.id(), AdminAuthCrypto.sha256("wrong"), null, null)).getType());
    }

    @Test void collisionAndWrongTypeCannotPartiallyPublishOrConsume() {
        Fixture f = pending();
        assertNull(store.publish(f.id(), AdminAuthCrypto.sha256("different"), f.secretHash(), new byte[8],
                Duration.ofMinutes(2), Duration.ofMinutes(5), 1500));
        assertFalse(redis.hasKey(RedisKeyConstant.adminQrScene(AdminAuthCrypto.sha256("different"))));
        redis.opsForValue().set(RedisKeyConstant.adminQrActivation(f.id()), "synthetic-wrongtype");
        assertThrows(SystemException.class, () -> web(f, "cancel"));
        assertEquals("PENDING", redis.opsForHash().get(RedisKeyConstant.adminQrSession(f.id()), "status"));
        assertThrows(SystemException.class, () -> store.activated(f.id(), f.secretHash(), 1));
    }

    @Test void confirmedAdmissionLossRejectsButDatabaseFailureDoesNot() {
        long subject = subjects.incrementAndGet(); Fixture f = confirmed(subject);
        when(users.getUserProfile(subject)).thenThrow(new SystemException("Synthetic database failure"));
        assertThrows(SystemException.class, () -> service.consumeSession(f.id(), f.secret(), "synthetic-db"));
        assertEquals(CONFIRMED, web(f, "read").session().status());
        UserProfileBO denied = profile(subject); denied.setRole(UserRole.USER);
        doReturn(denied).when(users).getUserProfile(subject);
        assertEquals("/problems/admin-access-denied", assertThrows(ContractProblemException.class,
                () -> service.consumeSession(f.id(), f.secret(), "synthetic-db")).getType());
        assertEquals(REJECTED, web(f, "read").session().status());
    }

    @Test void fixedWindowRateLimitHasRetryAfterAndDoesNotRenew() {
        String unique = UUID.randomUUID().toString(); store.limit("test", unique, 1);
        var limit = assertThrows(AdminQrLoginStore.RateLimited.class, () -> store.limit("test", unique, 1));
        assertTrue(limit.retryAfter() >= 1 && limit.retryAfter() <= 60);
        assertTrue(redis.getExpire(RedisKeyConstant.adminQrRate("test", AdminAuthCrypto.sha256(unique))) <= 60);
    }

    @Test void createKeepsIndependentCredentialsHasPrivateCachedImageAndProviderFailureDoesNotPublish() throws Exception {
        var props = new AdminQrLoginProperties(); props.setEnabled(true); props.setEnvVersion("trial"); props.validate();
        WxClient wx = mock(WxClient.class);
        var bytes = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(430, 430, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", bytes);
        when(wx.generateMiniProgramCode(any(), any(), any(), anyBoolean())).thenReturn(bytes.toByteArray());
        var enabled = new AdminQrLoginServiceImpl(store, tokens, users, new AdminPermissionPolicy(), props, wx, registry);
        var created = enabled.createSession("synthetic-create");
        assertEquals(22, created.session().sessionId().length()); assertEquals(43, created.pollSecret().length());
        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(wx).generateMiniProgramCode(eq(AdminQrLoginProperties.PAGE), captor.capture(), eq("trial"), eq(true));
        assertTrue(captor.getValue().matches("[A-Za-z0-9_-]{32}"));
        var stored = redis.opsForHash().entries(RedisKeyConstant.adminQrSession(created.session().sessionId()));
        assertFalse(stored.containsValue(captor.getValue())); assertFalse(stored.containsValue(created.pollSecret()));
        assertArrayEquals(bytes.toByteArray(), enabled.readCode(created.session().sessionId(), created.pollSecret(), "synthetic-create"));
        assertEquals(PENDING, enabled.querySession(created.session().sessionId(), created.pollSecret(), "synthetic-create").status());
        assertThrows(ContractProblemException.class, () -> enabled.querySession(created.session().sessionId(), captor.getValue(), "synthetic-create"));
        enabled.scanSession(captor.getValue(), subjects.incrementAndGet(), "synthetic-create");
        assertArrayEquals(bytes.toByteArray(), enabled.readCode(created.session().sessionId(), created.pollSecret(), "synthetic-create"));
        redis.opsForValue().set(RedisKeyConstant.adminQrImage(created.session().sessionId()), "synthetic-corrupt-image");
        assertThrows(SystemException.class, () -> enabled.readCode(created.session().sessionId(), created.pollSecret(), "synthetic-create"));
        doThrow(AdminQrLoginStore.problem(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "code-unavailable"))
                .when(wx).generateMiniProgramCode(any(), any(), any(), anyBoolean());
        assertThrows(ContractProblemException.class, () -> enabled.createSession("synthetic-create-failed"));
    }

    @Test void invalidConfigCannotEnableUnspecifiedEnvironmentOrUnboundedExpiry() {
        var config = new AdminQrLoginProperties(); config.validate(); assertFalse(config.isEnabled());
        config.setEnabled(true); assertThrows(IllegalArgumentException.class, config::validate);
        config.setEnvVersion("trial"); config.validate();
        config.setSessionTtl(Duration.ofMinutes(6)); assertThrows(IllegalArgumentException.class, config::validate);
        config.setSessionTtl(Duration.ofMinutes(2)); config.setRetention(Duration.ofMinutes(4)); assertThrows(IllegalArgumentException.class, config::validate);
    }

    @Test void invalidSecretsCannotSpendOwnerBucketAndRedisFailureIsNeverCredentialAbsence() {
        Fixture f = pending(); String invalid = AdminAuthCrypto.randomUrlToken(32);
        for (int i = 0; i < 70; i++) {
            assertEquals("/problems/qr-login-invalid-web-credential", assertThrows(ContractProblemException.class,
                    () -> service.querySession(f.id(), invalid, "synthetic-invalid-" + f.id())).getType());
        }
        assertFalse(redis.hasKey(RedisKeyConstant.adminQrRate("poll-session", AdminAuthCrypto.sha256(f.id()))));
        assertEquals(PENDING, service.querySession(f.id(), f.secret(), "synthetic-invalid-" + f.id()).status());
        StringRedisTemplate failed = mock(StringRedisTemplate.class);
        doThrow(new org.springframework.data.redis.RedisConnectionFailureException("Synthetic connection loss"))
                .when(failed).execute(org.mockito.ArgumentMatchers.<org.springframework.data.redis.core.RedisCallback<Object>>any());
        AdminQrLoginStore unavailable = new AdminQrLoginStore(failed, registry);
        assertThrows(org.springframework.data.redis.RedisConnectionFailureException.class,
                () -> unavailable.web("read", f.id(), f.secretHash(), null, null));
        assertThrows(org.springframework.data.redis.RedisConnectionFailureException.class,
                () -> unavailable.activated(f.id(), f.secretHash(), 1));
    }

    @Test void disposableRedisRestartRetainsConsumedResultAndActivation() throws Exception {
        String container = System.getProperty("admin.qr.test.container");
        Assumptions.assumeTrue("jualn-admin-qr-v2-test".equals(container), "Explicit disposable container required");
        long subject = subjects.incrementAndGet(); Fixture f = confirmed(subject);
        var original = service.consumeSession(f.id(), f.secret(), "synthetic-redis-restart");
        Process restart = new ProcessBuilder("docker", "restart", container).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try { assertTrue(restart.waitFor(25, TimeUnit.SECONDS)); assertEquals(0, restart.exitValue()); }
        finally { if (restart.isAlive()) restart.destroyForcibly(); }
        factory.destroy();
        factory = new LettuceConnectionFactory("127.0.0.1", Integer.parseInt(System.getProperty("admin.qr.test.port")));
        factory.afterPropertiesSet(); factory.start(); redis = new StringRedisTemplate(factory);
        var dao = new SaTokenDaoForRedisTemplate(); dao.init(factory); SaManager.setSaTokenDao(dao);
        store = new AdminQrLoginStore(redis, registry);
        tokens = new AdminTokenService(users, new AdminPermissionPolicy(), new AdminAuthStore(redis),
                new AdminAuthProperties(), store, new ObjectMapper().findAndRegisterModules()); service = service(tokens);
        assertEquals(original, service.consumeSession(f.id(), f.secret(), "synthetic-redis-restart"));
        assertEquals(CONSUMED, web(f, "read").session().status());
        assertTrue(store.activated(f.id(), AdminAuthCrypto.sha256(original.token()), subject));
    }

    @Test void terminalRacesHaveOneWinnerAndNeverOverwriteTheOther() throws Exception {
        for (String competitor : List.of("reject", "cancel", "expire")) {
            Fixture f = pending(); long subject = subjects.incrementAndGet(); store.mobile("scan", f.sceneHash(), subject);
            concurrent(2, i -> {
                try {
                    if (i == 0) return store.mobile("confirm", f.sceneHash(), subject).session().status();
                    if (competitor.equals("reject")) return store.mobile("reject", f.sceneHash(), subject).session().status();
                    if (competitor.equals("cancel")) return web(f, "cancel").session().status();
                    expire(f); return web(f, "read").session().status();
                } catch (ContractProblemException expected) { return null; }
            });
            var after = web(f, "read").session().status();
            assertTrue(after == CONFIRMED || after == REJECTED || after == CANCELLED || after == EXPIRED);
            if (after.terminal()) {
                assertEquals(after, web(f, "cancel").session().status());
                assertThrows(ContractProblemException.class, () -> store.mobile("confirm", f.sceneHash(), subject));
            }
        }
        for (String competitor : List.of("cancel", "expire", "revoke")) {
            long subject = subjects.incrementAndGet(); Fixture f = confirmed(subject);
            var candidate = tokens.prepareQrCandidate(subject, f.id(), profile(subject), web(f, "read").now());
            store.web("reserve", f.id(), f.secretHash(), candidate, null); tokens.materializeQrCandidate(subject, candidate, web(f, "read").now());
            concurrent(2, i -> {
                try {
                    if (i == 0) return store.web("finalize", f.id(), f.secretHash(), candidate, tokens.qrMappingKey(candidate)).session().status();
                    if (competitor.equals("cancel")) return web(f, "cancel").session().status();
                    if (competitor.equals("expire")) expire(f);
                    else new AdminAuthStore(redis).revokeToken(candidate.tokenHash(), Duration.ofMinutes(5));
                    return web(f, "read").session().status();
                } catch (ContractProblemException | SystemException expected) { return null; }
            });
            var after = web(f, "read");
            if (after.session().status() != CONSUMED) assertFalse(store.activated(f.id(), candidate.tokenHash(), subject));
            if (competitor.equals("revoke")) assertFalse(tokens.validQrResult(subject, f.id(), candidate, after.now()));
        }
    }

    @Test void consumedExpiryAndSessionCollectionDoNotRevokeNormalAdminLifetime() {
        long subject = subjects.incrementAndGet(); Fixture f = confirmed(subject);
        var result = service.consumeSession(f.id(), f.secret(), "synthetic-retention");
        var candidate = web(f, "read").candidate(); expire(f);
        assertEquals(CONSUMED, web(f, "read").session().status());
        assertEquals("/problems/qr-login-session-already-consumed", assertThrows(ContractProblemException.class,
                () -> service.consumeSession(f.id(), f.secret(), "synthetic-retention")).getType());
        redis.delete(RedisKeyConstant.adminQrSession(f.id()));
        assertTrue(tokens.validQrResult(subject, f.id(), candidate, System.currentTimeMillis()));
        SaTokenContextMockUtil.setMockContext();
        ((SaRequestForMock) SaHolder.getRequest()).headerMap.put("Authorization", "Bearer " + result.token());
        assertEquals(Long.toString(subject), tokens.requireValidLogin().getId());
        assertEquals("/problems/qr-login-invalid-web-credential", assertThrows(ContractProblemException.class, () -> web(f, "read")).getType());
    }

    @Test void corruptedOrMissingActivationAndForgedMarkerFailClosed() {
        long subject = subjects.incrementAndGet(); Fixture f = confirmed(subject);
        var result = service.consumeSession(f.id(), f.secret(), "synthetic-corruption");
        int signature = result.token().lastIndexOf('.') + 1;
        String forged = result.token().substring(0, signature) + (result.token().charAt(signature) == 'A' ? 'B' : 'A') + result.token().substring(signature + 1);
        SaManager.getSaTokenDao().set(AdminStpUtil.STP_LOGIC.splicingKeyTokenValue(forged), Long.toString(subject), 60);
        SaTokenContextMockUtil.setMockContext();
        ((SaRequestForMock) SaHolder.getRequest()).headerMap.put("Authorization", "Bearer " + forged);
        assertThrows(BusinessException.class, tokens::requireValidLogin);
        SaTokenContextMockUtil.clearContext();
        redis.delete(RedisKeyConstant.adminQrActivation(f.id()));
        assertEquals("/problems/qr-login-session-already-consumed", assertThrows(ContractProblemException.class,
                () -> service.consumeSession(f.id(), f.secret(), "synthetic-corruption")).getType());
        var same = web(f, "read"); assertEquals(result.token(), same.candidate().token()); assertEquals(CONSUMED, same.session().status());
        redis.opsForHash().delete(RedisKeyConstant.adminQrSession(f.id()), "candidateProfileJson");
        assertThrows(SystemException.class, () -> web(f, "cancel"));
        assertEquals("CONSUMED", redis.opsForHash().get(RedisKeyConstant.adminQrSession(f.id()), "status"));
    }

    @Test void independentJvmCrashesAfterReserveLoginAndCommitRecoverOneSavedResult() throws Exception {
        for (String boundary : List.of("reserve", "partial", "materialize", "finalize")) {
            long subject = subjects.incrementAndGet(); Fixture f = confirmed(subject);
            String javaExecutable = java.nio.file.Path.of(System.getProperty("java.home"), "bin",
                    System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
            String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
            Process process = new ProcessBuilder(javaExecutable, "-cp", classpath, CrashProcess.class.getName(),
                    System.getProperty("admin.qr.test.port"), f.id(), f.secretHash(), Long.toString(subject), boundary)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            try {
                assertTrue(process.waitFor(30, TimeUnit.SECONDS)); assertEquals(19, process.exitValue());
                var saved = web(f, "read"); assertNotNull(saved.candidate());
                if (!boundary.equals("finalize")) assertFalse(store.activated(f.id(), saved.candidate().tokenHash(), subject));
                var recovered = service(tokens).consumeSession(f.id(), f.secret(), "synthetic-process-" + boundary);
                assertEquals(saved.candidate().token(), recovered.token());
                assertEquals(recovered, service(tokens).consumeSession(f.id(), f.secret(), "synthetic-process-" + boundary));
                assertEquals(CONSUMED, web(f, "read").session().status());
            } finally { if (process.isAlive()) process.destroyForcibly(); }
        }
    }

    /** Child dies without shutdown hooks, between real Redis/Sa-Token effects and acknowledgement. */
    public static class CrashProcess {
        public static void main(String[] args) {
            LettuceConnectionFactory connection = new LettuceConnectionFactory("127.0.0.1", Integer.parseInt(args[0]));
            connection.afterPropertiesSet(); connection.start();
            StringRedisTemplate redis = new StringRedisTemplate(connection);
            SaTokenDaoForRedisTemplate dao = new SaTokenDaoForRedisTemplate() {
                @Override public void set(String key, String value, long timeout) {
                    super.set(key, value, timeout);
                    if (args[4].equals("partial") && key.equals(AdminStpUtil.STP_LOGIC.splicingKeySession(Long.parseLong(args[3])))) {
                        Runtime.getRuntime().halt(19);
                    }
                }
            };
            dao.init(connection);
            SaManager.setSaTokenDao(dao); SaManager.setSaJsonTemplate(new cn.dev33.satoken.json.SaJsonTemplateForJackson());
            SaManager.setConfig(new SaTokenConfig().setTokenName("Authorization").setTokenPrefix("Bearer")
                    .setJwtSecretKey("synthetic-isolated-admin-qr-test-secret-1234567890").setIsConcurrent(true).setIsShare(false));
            AdminQrLoginStore store = new AdminQrLoginStore(redis, new SimpleMeterRegistry());
            AdminTokenService tokens = new AdminTokenService(mock(UserService.class), new AdminPermissionPolicy(),
                    new AdminAuthStore(redis), new AdminAuthProperties(), store, new ObjectMapper());
            long subject = Long.parseLong(args[3]);
            UserProfileBO user = UserProfileBO.builder().id(subject).nickname("Synthetic operator").role(UserRole.ADMIN).status(UserStatus.NORMAL).build();
            var current = store.web("read", args[1], args[2], null, null);
            var prepared = tokens.prepareQrCandidate(subject, args[1], user, current.now());
            var reserved = store.web("reserve", args[1], args[2], prepared, null);
            if (!args[4].equals("reserve")) tokens.materializeQrCandidate(subject, reserved.candidate(), reserved.now());
            if (args[4].equals("finalize")) store.web("finalize", args[1], args[2], reserved.candidate(), tokens.qrMappingKey(reserved.candidate()));
            Runtime.getRuntime().halt(19);
        }
    }
}
