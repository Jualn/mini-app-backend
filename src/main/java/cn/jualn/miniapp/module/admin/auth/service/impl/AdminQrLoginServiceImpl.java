package cn.jualn.miniapp.module.admin.auth.service.impl;

import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.common.exception.SystemException;
import cn.jualn.miniapp.common.observability.ObservabilityContext;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.infrastructure.cache.AdminQrLoginStore;
import cn.jualn.miniapp.infrastructure.cache.AdminQrLoginStore.Snapshot;
import cn.jualn.miniapp.module.admin.auth.bo.qrlogin.*;
import cn.jualn.miniapp.module.admin.auth.config.AdminQrLoginProperties;
import cn.jualn.miniapp.module.admin.auth.enums.AdminQrLoginV2Status;
import cn.jualn.miniapp.module.admin.auth.service.*;
import cn.jualn.miniapp.module.admin.auth.support.*;
import cn.jualn.miniapp.module.user.bo.UserProfileBO;
import cn.jualn.miniapp.module.user.service.UserService;
import cn.jualn.miniapp.third.wx.client.WxClient;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.concurrent.Semaphore;

import static cn.jualn.miniapp.infrastructure.cache.AdminQrLoginStore.problem;
import static cn.jualn.miniapp.module.admin.auth.enums.AdminQrLoginV2Status.*;

@Slf4j
@Service
public class AdminQrLoginServiceImpl implements AdminQrLoginService {
    private final AdminQrLoginStore store;
    private final AdminTokenService tokens;
    private final UserService users;
    private final AdminPermissionPolicy policy;
    private final AdminQrLoginProperties properties;
    private final WxClient wx;
    private final MeterRegistry meters;
    private final Semaphore generation;

    public AdminQrLoginServiceImpl(AdminQrLoginStore store, AdminTokenService tokens, UserService users,
            AdminPermissionPolicy policy, AdminQrLoginProperties properties, WxClient wx, MeterRegistry meters) {
        this.store = store; this.tokens = tokens; this.users = users; this.policy = policy;
        this.properties = properties; this.wx = wx; this.meters = meters;
        generation = new Semaphore(properties.getGenerationConcurrency());
    }

    @Override public AdminQrCreatedBO createSession(String ip) {
        store.limit("create-ip", ip, properties.getCreateIpLimit());
        store.limit("create-global", "global", properties.getCreateGlobalLimit());
        if (!properties.isEnabled() || !generation.tryAcquire()) throw problem(HttpStatus.SERVICE_UNAVAILABLE, "code-unavailable");
        try {
            store.requireSameTokenStorage();
            for (int i = 0; i < 3; i++) {
                String id = AdminAuthCrypto.randomUrlToken(16), scene = AdminAuthCrypto.randomUrlToken(24), secret = AdminAuthCrypto.randomUrlToken(32);
                byte[] png;
                try { png = wx.generateMiniProgramCode(AdminQrLoginProperties.PAGE, scene, properties.getEnvVersion(), properties.isCheckPath()); }
                catch (cn.jualn.miniapp.common.exception.ExternalServiceException failure) {
                    throw problem(HttpStatus.SERVICE_UNAVAILABLE, "code-unavailable");
                }
                Snapshot saved = store.publish(id, AdminAuthCrypto.sha256(scene), AdminAuthCrypto.sha256(secret), png,
                        properties.getSessionTtl(), properties.getRetention(), properties.getPollIntervalMs());
                if (saved != null) {
                    Snapshot readable = store.web("code", id, AdminAuthCrypto.sha256(secret), null, null);
                    if (readable.session().status() != PENDING || readable.image().length == 0) throw new SystemException("QR publication unavailable");
                    log.info("result=success operation=qrCreate sessionId={}", id);
                    return new AdminQrCreatedBO(saved.session(), secret);
                }
            }
            throw new SystemException("Repeated QR credential collision");
        } finally { generation.release(); }
    }

    @Override public AdminQrSessionBO querySession(String id, String secret, String ip) {
        return owner("poll", id, secret, ip, 120, 60).session();
    }
    @Override public byte[] readCode(String id, String secret, String ip) {
        owner("code", id, secret, ip, 30, 10);
        byte[] image = store.web("code", id, hash(secret), null, null).image();
        try { WxClient.normalizeMiniCode(image); }
        catch (RuntimeException failure) { throw new SystemException("Corrupt cached QR PNG"); }
        return image;
    }
    @Override public AdminQrSessionBO cancelSession(String id, String secret, String ip) {
        owner("cancel", id, secret, ip, 30, 10);
        Snapshot cancelled = store.web("cancel", id, hash(secret), null, null);
        discardUncommitted(cancelled);
        return cancelled.session();
    }
    @Override public AdminQrSessionBO scanSession(String scene, long subject, String ip) {
        store.limit("scan-ip", ip, 60);
        store.limit("scan-subject", Long.toString(subject), 30);
        return store.mobile("scan", sceneHash(scene), subject).session();
    }
    @Override public AdminQrSessionBO confirmSession(String scene, long subject, String ip) {
        store.limit("confirm-subject", Long.toString(subject), 10);
        String sceneHash = sceneHash(scene);
        Snapshot state = store.mobile("inspect", sceneHash, subject);
        if (state.session().status() == CONSUMED) return state.session();
        assertUsable(state);
        if (state.session().status() == CONFIRMED) return store.mobile("confirm", sceneHash, subject).session();
        if (state.session().status() != SCANNED || state.userId() == null) throw problem(HttpStatus.CONFLICT, "invalid-state");
        if (!policy.canLogin(profile(subject))) {
            store.mobile("denyMobile", sceneHash, subject);
            throw denied();
        }
        Snapshot confirmed = store.mobile("confirm", sceneHash, subject);
        log.debug("result=success operation=qrConfirm sessionId={}", confirmed.session().sessionId());
        return confirmed.session();
    }
    @Override public AdminQrSessionBO rejectSession(String scene, long subject, String ip) {
        store.limit("reject-subject", Long.toString(subject), 10);
        return store.mobile("reject", sceneHash(scene), subject).session();
    }

    @Override public AdminQrLoginResultBO consumeSession(String id, String secret, String ip) {
        var previous = ObservabilityContext.capture();
        try {
            Snapshot state = owner("consume", id, secret, ip, 60, 20);
            MDC.put(ObservabilityContext.OPERATION_ID, id);
            assertUsable(state);
            if (state.session().status() != CONFIRMED && state.session().status() != CONSUMED) throw problem(HttpStatus.CONFLICT, "invalid-state");
            UserProfileBO current = requireAdmission(state, hash(secret));
            if (state.session().status() == CONSUMED) return replay(state, hash(secret));
            boolean recovering = state.candidate() != null;
            AdminQrCandidateBO candidate = recovering ? state.candidate()
                    : tokens.prepareQrCandidate(state.userId(), id, current, state.now());
            state = store.web("reserve", id, hash(secret), candidate, null);
            if (state.session().status() == CONSUMED) return replay(state, hash(secret));
            candidate = state.candidate();
            // Only the reserved candidate may materialize. Unknown results retain this candidate for the next request.
            tokens.materializeQrCandidate(state.userId(), candidate, state.now());
            state = store.web("read", id, hash(secret), null, null);
            discardUncommitted(state);
            assertUsable(state);
            requireAdmission(state, hash(secret));
            if (state.session().status() == CONSUMED) return replay(state, hash(secret));
            state = store.web("finalize", id, hash(secret), candidate, tokens.qrMappingKey(candidate));
            requireAdmission(state, hash(secret));
            if (!tokens.validQrResult(state.userId(), id, state.candidate(), state.now())) throw problem(HttpStatus.CONFLICT, "session-already-consumed");
            String outcome = state.outcome().equals("COMMITTED") ? (recovering ? "recovered" : "committed") : "replayed";
            count(outcome);
            if (!outcome.equals("replayed")) log.info("result=success operation=qrConsume sessionId={} recovery={}", id, recovering);
            return tokens.qrResult(state.candidate());
        } catch (ContractProblemException e) {
            if (e.getStatus() == HttpStatus.FORBIDDEN) count("denied");
            else if (e.getStatus() == HttpStatus.CONFLICT) count("conflict");
            throw e;
        } finally { ObservabilityContext.install(previous); }
    }

    private AdminQrLoginResultBO replay(Snapshot state, String secretHash) {
        requireAdmission(state, secretHash);
        state = store.web("read", state.session().sessionId(), secretHash, null, null);
        assertUsable(state);
        // The caller already checked current admission; authorization is never taken from frozen profile JSON.
        if (state.candidate() == null || !tokens.validQrResult(state.userId(), state.session().sessionId(), state.candidate(), state.now())) {
            throw problem(HttpStatus.CONFLICT, "session-already-consumed");
        }
        count("replayed");
        return tokens.qrResult(state.candidate());
    }

    private UserProfileBO requireAdmission(Snapshot state, String secretHash) {
        if (state.userId() == null) throw new SystemException("QR login subject missing");
        UserProfileBO profile = profile(state.userId());
        if (policy.canLogin(profile)) return profile;
        log.info("result=denied errorCategory=authorization operation=qrAdmission sessionId={}", state.session().sessionId());
        if (state.session().status() == CONFIRMED) discardUncommitted(
                store.web("denyWeb", state.session().sessionId(), secretHash, null, null));
        throw denied();
    }

    private void discardUncommitted(Snapshot state) {
        if (state.candidate() == null || (state.session().status() != CANCELLED
                && state.session().status() != EXPIRED && state.session().status() != REJECTED)) return;
        try { tokens.discardQrCandidate(state.candidate()); }
        catch (RuntimeException failure) {
            log.warn("result=failure errorCategory={} operation=qrCandidateCleanup sessionId={}",
                    failure instanceof org.springframework.dao.DataAccessException ? "redis" : "internal", state.session().sessionId());
        }
    }

    private UserProfileBO profile(long subject) {
        try { return users.getUserProfile(subject); }
        catch (BusinessException e) { if (e.getResultCode() == ResultCode.USER_NOT_FOUND) return null; throw e; }
    }

    private Snapshot owner(String op, String id, String secret, String ip, int ipLimit, int sessionLimit) {
        if (id == null || id.isEmpty() || id.length() > 128) throw ContractProblemException.validation(
                new ContractProblemException.Violation("path", "/sessionId", "INVALID", "Invalid sessionId"));
        store.limit(op + "-ip", ip, ipLimit);
        if (secret == null || !secret.matches("[A-Za-z0-9_-]{43}")) throw problem(HttpStatus.UNAUTHORIZED, "invalid-web-credential");
        Snapshot state = store.web("read", id, hash(secret), null, null);
        store.limit(op + "-session", id, sessionLimit);
        return state;
    }

    private void assertUsable(Snapshot state) {
        AdminQrLoginV2Status status = state.session().status();
        if (status == CONSUMED && state.now() >= state.session().expiresAt()) throw problem(HttpStatus.CONFLICT, "session-already-consumed");
        if (status == EXPIRED) throw problem(HttpStatus.CONFLICT, "session-expired");
        if (status == CANCELLED) throw problem(HttpStatus.CONFLICT, "session-cancelled");
        if (status == REJECTED) throw problem(HttpStatus.CONFLICT, "session-rejected");
    }
    private String sceneHash(String scene) {
        if (scene == null || !scene.matches("[A-Za-z0-9_-]{32}")) throw ContractProblemException.validation(
                new ContractProblemException.Violation("body", "/sceneCode", "INVALID", "Invalid sceneCode"));
        return hash(scene);
    }
    private String hash(String input) { return AdminAuthCrypto.sha256(input); }
    private ContractProblemException denied() {
        return new ContractProblemException(HttpStatus.FORBIDDEN, "/problems/admin-access-denied", "Admin access denied");
    }
    private void count(String result) { meters.counter("jualn.admin.qr.login.consume", "result", result).increment(); }
}
