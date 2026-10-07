package cn.jualn.miniapp.infrastructure.cache;

import cn.jualn.miniapp.common.constant.RedisKeyConstant;
import cn.jualn.miniapp.common.exception.SystemException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import java.time.Duration;

/** 管理端 Token 撤销记录；二维码状态由 AdminQrLoginStore 持有。 */
@Component
@RequiredArgsConstructor
public class AdminAuthStore {
    private final StringRedisTemplate redisTemplate;

    public void revokeToken(String tokenHash, Duration ttl) {
        try {
            redisTemplate.opsForValue().set(RedisKeyConstant.adminRevokedToken(tokenHash), "1", ttl);
        } catch (RuntimeException e) {
            throw new SystemException("管理端 Token 注销状态写入失败", e);
        }
    }

    public boolean isTokenRevoked(String tokenHash) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(RedisKeyConstant.adminRevokedToken(tokenHash)));
        } catch (RuntimeException e) {
            throw new SystemException("管理端 Token 注销状态读取失败", e);
        }
    }

}
