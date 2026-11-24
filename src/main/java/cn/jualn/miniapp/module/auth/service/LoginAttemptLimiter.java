package cn.jualn.miniapp.module.auth.service;

import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.common.exception.RateLimitedException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Login boundary only. Never key by code, openid, credential or untrusted forwarding headers. */
@Service @RequiredArgsConstructor
public class LoginAttemptLimiter {
    private final StringRedisTemplate redis;
    @Value("${app.auth.login-attempts-per-minute:30}") private int limit = 30;
    private static final DefaultRedisScript<Long> SCRIPT = new DefaultRedisScript<>(
        "local n=redis.call('INCR',KEYS[1]); if n==1 then redis.call('EXPIRE',KEYS[1],60) end; return n", Long.class);
    public void require(String peer) {
        try {
            if (limit < 1 || peer == null) throw new IllegalStateException();
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(peer.getBytes(StandardCharsets.UTF_8)));
            Long count = redis.execute(SCRIPT, List.of(cn.jualn.miniapp.common.constant.RedisKeyConstant.LOGIN_ATTEMPT_RATE_PREFIX + digest));
            if (count == null) throw new IllegalStateException();
            if (count > limit) throw new RateLimitedException("Login rate exceeded", 60);
        } catch (RateLimitedException limited) { throw limited; }
        catch (Exception failure) {
            throw new ContractProblemException(HttpStatus.SERVICE_UNAVAILABLE, "/problems/authentication-unavailable", "Authentication cannot be verified");
        }
    }
}
