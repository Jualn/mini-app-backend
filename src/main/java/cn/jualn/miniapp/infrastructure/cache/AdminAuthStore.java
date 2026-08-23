package cn.jualn.miniapp.infrastructure.cache;

import cn.jualn.miniapp.common.exception.SystemException;
import cn.jualn.miniapp.module.admin.auth.enums.AdminQrLoginStatus;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * 管理端认证的 Redis 存储。
 *
 * <p>二维码会话的状态迁移通过 Lua 在 Redis 内原子执行，避免并发轮询重复领取 Token。</p>
 */
@Component
public class AdminAuthStore {

    private static final String SESSION_PREFIX = "jualn:admin:qr-login:";
    private static final String RATE_LIMIT_PREFIX = "jualn:admin:auth:rate:";
    private static final String REVOKED_TOKEN_PREFIX = "jualn:admin:auth:revoked:";

    private static final DefaultRedisScript<Long> CREATE_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 1 then
                return 0
            end
            redis.call('HSET', KEYS[1],
                'status', 'PENDING',
                'pollSecretHash', ARGV[1],
                'expiresAt', ARGV[2])
            redis.call('PEXPIRE', KEYS[1], ARGV[3])
            return 1
            """, Long.class);

    private static final DefaultRedisScript<List> POLL_SCRIPT = listScript("""
            if redis.call('EXISTS', KEYS[1]) == 0 then
                return {'MISSING'}
            end
            local expected = redis.call('HGET', KEYS[1], 'pollSecretHash')
            local actual = ARGV[1]
            if not expected or string.len(expected) ~= string.len(actual) then
                return {'MISSING'}
            end
            local different = 0
            for i = 1, string.len(expected) do
                if string.byte(expected, i) ~= string.byte(actual, i) then
                    different = 1
                end
            end
            if different ~= 0 then
                return {'MISSING'}
            end
            local expiresAt = tonumber(redis.call('HGET', KEYS[1], 'expiresAt'))
            if tonumber(ARGV[2]) >= expiresAt then
                redis.call('DEL', KEYS[1])
                return {'EXPIRED'}
            end
            return {
                redis.call('HGET', KEYS[1], 'status'),
                redis.call('HGET', KEYS[1], 'userId') or '',
                redis.call('HGET', KEYS[1], 'deniedReason') or ''
            }
            """);

    private static final DefaultRedisScript<List> CONFIRM_SCRIPT = listScript("""
            if redis.call('EXISTS', KEYS[1]) == 0 then
                return {'MISSING'}
            end
            local expiresAt = tonumber(redis.call('HGET', KEYS[1], 'expiresAt'))
            if tonumber(ARGV[1]) >= expiresAt then
                redis.call('DEL', KEYS[1])
                return {'EXPIRED'}
            end
            local status = redis.call('HGET', KEYS[1], 'status')
            if status == 'PENDING' or status == 'SCANNED' then
                redis.call('HSET', KEYS[1], 'status', 'CONFIRMED', 'userId', ARGV[2])
                return {'CONFIRMED', ARGV[2], ''}
            end
            return {
                status,
                redis.call('HGET', KEYS[1], 'userId') or '',
                redis.call('HGET', KEYS[1], 'deniedReason') or ''
            }
            """);

    private static final DefaultRedisScript<List> DENY_SCRIPT = listScript("""
            if redis.call('EXISTS', KEYS[1]) == 0 then
                return {'MISSING'}
            end
            local expiresAt = tonumber(redis.call('HGET', KEYS[1], 'expiresAt'))
            if tonumber(ARGV[1]) >= expiresAt then
                redis.call('DEL', KEYS[1])
                return {'EXPIRED'}
            end
            local status = redis.call('HGET', KEYS[1], 'status')
            if status == 'PENDING' or status == 'SCANNED' or status == 'CONFIRMED' then
                redis.call('HSET', KEYS[1], 'status', 'DENIED', 'deniedReason', ARGV[2])
                redis.call('HDEL', KEYS[1], 'userId')
                return {'DENIED', '', ARGV[2]}
            end
            return {
                status,
                redis.call('HGET', KEYS[1], 'userId') or '',
                redis.call('HGET', KEYS[1], 'deniedReason') or ''
            }
            """);

    private static final DefaultRedisScript<List> CLAIM_SCRIPT = listScript("""
            if redis.call('EXISTS', KEYS[1]) == 0 then
                return {'MISSING'}
            end
            local expected = redis.call('HGET', KEYS[1], 'pollSecretHash')
            local actual = ARGV[1]
            if not expected or string.len(expected) ~= string.len(actual) then
                return {'MISSING'}
            end
            local different = 0
            for i = 1, string.len(expected) do
                if string.byte(expected, i) ~= string.byte(actual, i) then
                    different = 1
                end
            end
            if different ~= 0 then
                return {'MISSING'}
            end
            local expiresAt = tonumber(redis.call('HGET', KEYS[1], 'expiresAt'))
            if tonumber(ARGV[2]) >= expiresAt then
                redis.call('DEL', KEYS[1])
                return {'EXPIRED'}
            end
            if redis.call('HGET', KEYS[1], 'status') ~= 'CONFIRMED' then
                return {'NOT_CONFIRMED'}
            end
            local userId = redis.call('HGET', KEYS[1], 'userId')
            redis.call('DEL', KEYS[1])
            return {'CLAIMED', userId}
            """);

    private static final DefaultRedisScript<Long> CANCEL_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 0 then
                return 0
            end
            local expected = redis.call('HGET', KEYS[1], 'pollSecretHash')
            local actual = ARGV[1]
            if not expected or string.len(expected) ~= string.len(actual) then
                return 0
            end
            local different = 0
            for i = 1, string.len(expected) do
                if string.byte(expected, i) ~= string.byte(actual, i) then
                    different = 1
                end
            end
            if different == 0 then
                redis.call('DEL', KEYS[1])
                return 1
            end
            return 0
            """, Long.class);

    private static final DefaultRedisScript<Long> RATE_LIMIT_SCRIPT = new DefaultRedisScript<>("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 or redis.call('PTTL', KEYS[1]) < 0 then
                redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            return count
            """, Long.class);

    private final StringRedisTemplate redisTemplate;

    public AdminAuthStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public boolean createSession(String sessionId,
                                 String pollSecretHash,
                                 long expiresAtEpochMs,
                                 Duration redisTtl) {
        Long created = execute(
                CREATE_SCRIPT,
                List.of(sessionKey(sessionId)),
                pollSecretHash,
                Long.toString(expiresAtEpochMs),
                Long.toString(redisTtl.toMillis())
        );
        return Long.valueOf(1L).equals(created);
    }

    public SessionState poll(String sessionId, String pollSecretHash, long nowEpochMs) {
        List<?> result = execute(
                POLL_SCRIPT,
                List.of(sessionKey(sessionId)),
                pollSecretHash,
                Long.toString(nowEpochMs)
        );
        return parseState(result);
    }

    public SessionState confirm(String sessionId, long userId, long nowEpochMs) {
        List<?> result = execute(
                CONFIRM_SCRIPT,
                List.of(sessionKey(sessionId)),
                Long.toString(nowEpochMs),
                Long.toString(userId)
        );
        return parseState(result);
    }

    public SessionState deny(String sessionId, String reason, long nowEpochMs) {
        List<?> result = execute(
                DENY_SCRIPT,
                List.of(sessionKey(sessionId)),
                Long.toString(nowEpochMs),
                reason
        );
        return parseState(result);
    }

    public Long claim(String sessionId, String pollSecretHash, long nowEpochMs) {
        List<?> result = execute(
                CLAIM_SCRIPT,
                List.of(sessionKey(sessionId)),
                pollSecretHash,
                Long.toString(nowEpochMs)
        );
        if (result == null || result.size() < 2 || !"CLAIMED".equals(asString(result.get(0)))) {
            return null;
        }
        return Long.parseLong(asString(result.get(1)));
    }

    public void cancel(String sessionId, String pollSecretHash) {
        execute(CANCEL_SCRIPT, List.of(sessionKey(sessionId)), pollSecretHash);
    }

    public long incrementRateLimit(String discriminator, Duration window) {
        Long count = execute(
                RATE_LIMIT_SCRIPT,
                List.of(RATE_LIMIT_PREFIX + discriminator),
                Long.toString(window.toMillis())
        );
        if (count == null) {
            throw new SystemException("管理端认证限流计数失败");
        }
        return count;
    }

    public void revokeToken(String tokenHash, Duration ttl) {
        try {
            redisTemplate.opsForValue().set(REVOKED_TOKEN_PREFIX + tokenHash, "1", ttl);
        } catch (RuntimeException e) {
            throw new SystemException("管理端 Token 注销状态写入失败", e);
        }
    }

    public boolean isTokenRevoked(String tokenHash) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(REVOKED_TOKEN_PREFIX + tokenHash));
        } catch (RuntimeException e) {
            throw new SystemException("管理端 Token 注销状态读取失败", e);
        }
    }

    private SessionState parseState(List<?> result) {
        if (result == null || result.isEmpty()) {
            throw new SystemException("管理端二维码会话状态读取失败");
        }
        String statusValue = asString(result.get(0));
        if ("MISSING".equals(statusValue)) {
            return null;
        }
        AdminQrLoginStatus status = AdminQrLoginStatus.valueOf(statusValue);
        Long userId = result.size() > 1 && !asString(result.get(1)).isBlank()
                ? Long.parseLong(asString(result.get(1)))
                : null;
        String deniedReason = result.size() > 2 ? asString(result.get(2)) : null;
        return new SessionState(status, userId, deniedReason);
    }

    private <T> T execute(DefaultRedisScript<T> script, List<String> keys, String... args) {
        try {
            return redisTemplate.execute(script, keys, (Object[]) args);
        } catch (RuntimeException e) {
            throw new SystemException("管理端认证 Redis 操作失败", e);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static DefaultRedisScript<List> listScript(String script) {
        return new DefaultRedisScript(script, List.class);
    }

    private static String asString(Object value) {
        return value == null ? "" : value.toString();
    }

    private static String sessionKey(String sessionId) {
        return SESSION_PREFIX + sessionId;
    }

    public record SessionState(AdminQrLoginStatus status, Long userId, String deniedReason) {
    }
}
