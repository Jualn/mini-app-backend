package cn.jualn.miniapp.infrastructure.cache;

import cn.jualn.miniapp.common.constant.RedisKeyConstant;
import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.common.exception.SystemException;
import cn.jualn.miniapp.module.admin.auth.bo.qrlogin.*;
import cn.jualn.miniapp.module.admin.auth.enums.AdminQrLoginV2Status;
import cn.jualn.miniapp.module.admin.auth.support.AdminAuthCrypto;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Strict authentication storage; absence is never synthesized after a Redis failure. */
@Component
@lombok.extern.slf4j.Slf4j
public class AdminQrLoginStore {
    private final StringRedisTemplate redis;
    private final MeterRegistry meters;
    private final byte[] script = load("redis/admin-qr-login-v2.lua");
    private final byte[] rateScript = load("redis/admin-qr-login-rate.lua");

    public AdminQrLoginStore(StringRedisTemplate redis, MeterRegistry meters) {
        this.redis = redis;
        this.meters = meters;
        if (redis.getConnectionFactory() instanceof LettuceConnectionFactory factory
                && factory.getClusterConfiguration() != null) {
            throw new IllegalStateException("Admin QR login v2 requires non-Cluster Redis");
        }
    }

    public record Snapshot(AdminQrSessionBO session, Long userId, AdminQrCandidateBO candidate,
            long now, String outcome, byte[] image) {
        @Override public String toString() { return "AdminQrSnapshot[credentials redacted]"; }
    }

    public Snapshot publish(String id, String sceneHash, String secretHash, byte[] png,
            Duration ttl, Duration retention, long interval) {
        List<byte[]> args = bytes("publish", id, sceneHash, secretHash, ttl.toMillis(), retention.toMillis(), interval);
        args.add(png);
        List<byte[]> result = evaluate(script, keys(id, sceneHash, null), args);
        if (value(result, 0).equals("COLLISION")) return null;
        return decode(result, false);
    }

    public Snapshot web(String operation, String id, String secretHash, AdminQrCandidateBO candidate,
            String mappingKey) {
        List<byte[]> args = bytes(operation, id, secretHash);
        if (operation.equals("reserve")) {
            args.addAll(bytes(candidate.token(), candidate.tokenHash(), candidate.profileJson(), candidate.tokenExpiresAt()));
        } else if (operation.equals("finalize")) args.addAll(bytes(candidate.tokenHash()));
        return decode(evaluate(script, keys(id, "unused", mappingKey == null ? null
                : new String[] {mappingKey, RedisKeyConstant.adminRevokedToken(candidate.tokenHash())}), args), false);
    }

    public Snapshot mobile(String operation, String sceneHash, long subject) {
        String id = redis.opsForValue().get(RedisKeyConstant.adminQrScene(sceneHash));
        if (id == null) throw problem(HttpStatus.NOT_FOUND, "invalid-scene");
        return decode(evaluate(script, keys(id, sceneHash, null), bytes(operation, id, sceneHash, subject)), true);
    }

    public void limit(String operation, String discriminator, int maximum) {
        List<byte[]> result = evaluate(rateScript,
                List.of(RedisKeyConstant.adminQrRate(operation, AdminAuthCrypto.sha256(discriminator))), List.of());
        if (value(result, 0).equals("CORRUPT")) throw new SystemException("Corrupt QR login rate state");
        if (Long.parseLong(value(result, 0)) > maximum) {
            throw new RateLimited(Math.max(1, (Long.parseLong(value(result, 1)) + 999) / 1000));
        }
    }

    public static final class RateLimited extends RuntimeException {
        private final long retryAfter;
        public RateLimited(long retryAfter) { super("QR login rate limit exceeded"); this.retryAfter = retryAfter; }
        public long retryAfter() { return retryAfter; }
    }

    public boolean activated(String id, String hash, long userId) {
        // Atomic TIME-based verification, independent of QR session retention.
        byte[] gateScript = ("local t=redis.call('TYPE',KEYS[1]).ok; if t=='none' then return 0 end; "
                + "if t~='hash' then return -1 end; local v=redis.call('HMGET',KEYS[1],'tokenHash','userId','tokenExpiresAt'); "
                + "if not v[1] or #v[1]~=64 or not tonumber(v[2]) or not tonumber(v[3]) then return -1 end; "
                + "local tm=redis.call('TIME'); local now=tonumber(tm[1])*1000+math.floor(tonumber(tm[2])/1000); "
                + "if #ARGV[1]~=64 then return 0 end; local different=0; for i=1,64 do "
                + "if string.byte(v[1],i)~=string.byte(ARGV[1],i) then different=1 end end; "
                + "if different==0 and v[2]==ARGV[2] and tonumber(v[3])>now then return 1 end; return 0").getBytes(StandardCharsets.UTF_8);
        Long result = redis.execute((RedisCallback<Long>) connection -> connection.scriptingCommands().eval(
                gateScript, ReturnType.INTEGER, 1, utf8(RedisKeyConstant.adminQrActivation(id)), utf8(hash), utf8(userId)));
        if (result == null || result < 0) throw new SystemException("Corrupt QR login activation state");
        return result == 1;
    }

    public void deactivate(String id) { redis.delete(RedisKeyConstant.adminQrActivation(id)); }

    public void requireSameTokenStorage() {
        if (!(cn.dev33.satoken.SaManager.getSaTokenDao() instanceof cn.dev33.satoken.dao.SaTokenDaoForRedisTemplate dao)
                || dao.stringRedisTemplate == null
                || dao.stringRedisTemplate.getConnectionFactory() != redis.getConnectionFactory()) {
            throw new SystemException("QR and Sa-Token require the same Redis connection factory/database");
        }
    }

    private List<String> keys(String id, String sceneHash, String[] tokenKeys) {
        return List.of(RedisKeyConstant.adminQrSession(id), RedisKeyConstant.adminQrScene(sceneHash),
                RedisKeyConstant.adminQrImage(id), RedisKeyConstant.adminQrActivation(id),
                tokenKeys == null ? "jualn:admin:qr-login:v2:unused:mapping" : tokenKeys[0],
                tokenKeys == null ? "jualn:admin:qr-login:v2:unused:revocation" : tokenKeys[1]);
    }

    @SuppressWarnings("unchecked")
    private List<byte[]> evaluate(byte[] source, List<String> keys, List<byte[]> args) {
        List<byte[]> input = new ArrayList<>();
        keys.forEach(key -> input.add(utf8(key)));
        input.addAll(args);
        List<byte[]> result = redis.execute((RedisCallback<List<byte[]>>) connection ->
                connection.scriptingCommands().eval(source, ReturnType.MULTI, keys.size(), input.toArray(byte[][]::new)));
        if (result == null || result.isEmpty()) throw new SystemException("Missing QR login script result");
        return result;
    }

    private Snapshot decode(List<byte[]> result, boolean mobile) {
        String outcome = value(result, 0);
        if (outcome.equals("MISSING")) throw problem(mobile ? HttpStatus.NOT_FOUND : HttpStatus.UNAUTHORIZED,
                mobile ? "invalid-scene" : "invalid-web-credential");
        if (outcome.equals("SUBJECT_CONFLICT")) throw problem(HttpStatus.CONFLICT, "subject-conflict");
        if (outcome.equals("CORRUPT") || outcome.equals("NOT_READY")) throw new SystemException("QR login state unavailable");
        String from = value(result, 1), to = value(result, 3);
        if (!from.isEmpty()) {
            meters.counter("jualn.admin.qr.login.transition", "from", from, "to", to).increment();
            if (!to.equals("EXPIRED")) log.info("result=success operation=qrTransition sessionId={} from={} to={}", value(result, 2), from, to);
        }
        switch (outcome) {
            case "EXPIRED" -> throw problem(HttpStatus.CONFLICT, "session-expired");
            case "CANCELLED" -> throw problem(HttpStatus.CONFLICT, "session-cancelled");
            case "REJECTED" -> throw problem(HttpStatus.CONFLICT, "session-rejected");
            case "INVALID_STATE" -> throw problem(HttpStatus.CONFLICT, "invalid-state");
            default -> { }
        }
        AdminQrSessionBO session = new AdminQrSessionBO(value(result, 2), AdminQrLoginV2Status.valueOf(to),
                number(result, 4), number(result, 5), optionalNumber(result, 6), optionalNumber(result, 7));
        AdminQrCandidateBO candidate = value(result, 9).isEmpty() ? null
                : new AdminQrCandidateBO(value(result, 9), value(result, 10), value(result, 11), number(result, 12));
        return new Snapshot(session, optionalNumber(result, 8), candidate, number(result, 13), outcome,
                result.size() > 14 ? result.get(14) : new byte[0]);
    }

    public static ContractProblemException problem(HttpStatus status, String suffix) {
        return new ContractProblemException(status, "/problems/qr-login-" + suffix, "QR login: " + suffix);
    }
    private static byte[] load(String path) {
        try { return new ClassPathResource(path).getContentAsByteArray(); }
        catch (IOException e) { throw new IllegalStateException("Missing QR login script", e); }
    }
    private static List<byte[]> bytes(Object... values) {
        List<byte[]> result = new ArrayList<>();
        for (Object value : values) result.add(utf8(value));
        return result;
    }
    private static byte[] utf8(Object value) { return String.valueOf(value).getBytes(StandardCharsets.UTF_8); }
    private static String value(List<byte[]> result, int index) { return new String(result.get(index), StandardCharsets.UTF_8); }
    private static long number(List<byte[]> result, int index) { return Long.parseLong(value(result, index)); }
    private static Long optionalNumber(List<byte[]> result, int index) { return value(result, index).isEmpty() ? null : number(result, index); }
}
