package cn.jualn.miniapp.module.user.service;

import cn.jualn.miniapp.common.constant.RedisKeyConstant;
import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.module.user.bo.UserAgreementBO;
import cn.jualn.miniapp.module.user.entity.UserAgreement;
import cn.jualn.miniapp.module.user.mapper.UserAgreementMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.function.Supplier;

/** 当前用户协议同意记录的读写入口；持久化对象只留在本模块。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserAgreementService {
    private final UserAgreementMapper userAgreementMapper;
    private final RedisService redisService;

    /**
     * 当前登录用户同意协议。
     */
    @Transactional(rollbackFor = Exception.class)
    public void agreeCurrentAgreement(String version) {
        Long userId = requireUserId();
        log.debug("[UserAgreementService.agreeCurrentAgreement][开始] userId={}", userId);
        if (!StringUtils.hasText(version)) {
            throw new BusinessException(ResultCode.INVALID_OPERATION, "协议版本不能为空");
        }

        UserAgreement userAgreement = userAgreementMapper.selectById(userId);
        if (userAgreement == null) {
            userAgreement = UserAgreement.builder()
                    .userId(userId)
                    .version(version)
                    .build();
            userAgreementMapper.insert(userAgreement);
        } else {
            userAgreement.setVersion(version);
            userAgreement.setAgreedAt(LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")));
            userAgreementMapper.updateById(userAgreement);
        }

        redisService.delete(RedisKeyConstant.userAgreement(userId));
        log.debug("[UserAgreementService.agreeCurrentAgreement][完成] userId={}", userId);
    }

    /**
     * 查询当前登录用户协议记录。
     */
    public UserAgreementBO getCurrentAgreement() {
        Long userId = requireUserId();
        return loadCachedValue(
                RedisKeyConstant.userAgreement(userId),
                UserAgreementBO.class,
                RedisKeyConstant.USER_AGREEMENT_TTL,
                () -> {
                    UserAgreement row = userAgreementMapper.selectById(userId);
                    return row == null ? null : UserAgreementBO.builder().userId(row.getUserId())
                            .version(row.getVersion()).agreedAt(row.getAgreedAt()).build();
                }
        );
    }

    private Long requireUserId() {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        return userId;
    }

    private <T> T loadCachedValue(String key, Class<T> clazz, Duration ttl, Supplier<T> dbLoader) {
        // 读取缓存
        T cachedRaw = redisService.get(key, clazz);

        if (cachedRaw != null) {
            return cachedRaw;
        }

        // 读取数据库
        T dbValue = dbLoader.get();

        // 写入缓存
        redisService.set(key, dbValue, ttl);
        return dbValue;
    }
}
