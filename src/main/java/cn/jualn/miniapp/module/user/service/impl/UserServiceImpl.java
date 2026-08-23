package cn.jualn.miniapp.module.user.service.impl;

import cn.jualn.miniapp.common.constant.RedisKeyConstant;
import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.common.enums.UserRole;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.module.user.bo.*;
import cn.jualn.miniapp.module.user.converter.UserConverter;
import cn.jualn.miniapp.module.user.dto.inner.UserInfoDTO;
import cn.jualn.miniapp.module.user.entity.UserAgreement;
import cn.jualn.miniapp.module.user.entity.UserProfile;
import cn.jualn.miniapp.module.user.event.UserProfileUpdatedEvent;
import cn.jualn.miniapp.module.user.mapper.UserAgreementMapper;
import cn.jualn.miniapp.module.user.mapper.UserProfileMapper;
import cn.jualn.miniapp.module.user.service.UserService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Supplier;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserProfileMapper userProfileMapper;
    private final UserAgreementMapper userAgreementMapper;
    private final RedisService redisService;
    private final UserConverter userConverter;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 根据微信小程序 openid 获取用户基本信息。
     *
     * @param openid 微信小程序openid
     * @return 用户基本信息，用于拼接给登录返回
     */
    @Override
    public UserInfoDTO getUserInfo(String openid) {
        if (!StringUtils.hasText(openid)) {
            throw new BusinessException(ResultCode.INVALID_OPERATION, "openid 不能为空");
        }

        UserProfile userProfile = userProfileMapper.selectOne(
                new LambdaQueryWrapper<UserProfile>()
                        .select(UserProfile::getId, UserProfile::getNickname,
                                UserProfile::getAvatarUrl, UserProfile::getRole)
                        .eq(UserProfile::getOpenid, openid)
        );

        if (userProfile == null) {
            // 首次登录自动注册。
            String defaultUserName = "用户" + UUID.randomUUID().toString().substring(0, 6);
            userProfile = UserProfile.builder()
                    .openid(openid)
                    .nickname(defaultUserName)
                    .role(UserRole.USER.getCode())  // 只会回填 id，这里设置避免下方转化拿到null
                    .build();
            userProfileMapper.insert(userProfile);
        }

        return userConverter.toUserInfoDTO(userProfile);
    }

    /**
     * 获取当前登录用户资料。
     */
    @Override
    public UserProfileBO getCurrentProfile() {
        long start = System.currentTimeMillis();
        Long userId = requireUserId();
        log.info("[UserService.getCurrentProfile][开始] userId={}", userId);

        UserProfileBO profileBO = userConverter.toProfileBO(
                userProfileMapper.selectById(userId));
        if (profileBO == null) {
            log.warn("[UserService.getCurrentProfile][用户不存在] userId={}", userId);
            throw new BusinessException(ResultCode.USER_NOT_FOUND);
        }

        log.info("[UserService.getCurrentProfile][完成] userId={}, costMs={}", userId, System.currentTimeMillis() - start);
        return profileBO;
    }

    @Override
    public UserProfileBO getUserProfile(Long userId) {
        if (userId == null) {
            throw new BusinessException(ResultCode.USER_ID_REQUIRED);
        }
        UserProfileBO profileBO = userConverter.toProfileBO(userProfileMapper.selectById(userId));
        if (profileBO == null) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND);
        }
        return profileBO;
    }

    /**
     * 获取公开展示的用户资料。
     */
    @Override
    public UserPublicProfileBO getPublicProfile(Long userId) {
        if (userId == null) {
            throw new BusinessException(ResultCode.USER_ID_REQUIRED);
        }

        UserPublicProfileBO userProfile = loadCachedValue(
                RedisKeyConstant.userPublicProfile(userId),
                UserPublicProfileBO.class,
                RedisKeyConstant.USER_PUBLIC_PROFILE_TTL,
                () -> userProfileMapper.selectPublicProfileBOById(userId)
        );

        if (userProfile == null) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND);
        }
        return userProfile;
    }

    /**
     * 获取用户简版资料。
     */
    @Override
    public UserSimpleBO getSimpleInfo(Long userId) {
        if (userId == null) {
            throw new BusinessException(ResultCode.USER_ID_REQUIRED);
        }

        UserSimpleBO userSimpleBO = loadCachedValue(
                RedisKeyConstant.userSimpleProfile(userId),
                UserSimpleBO.class,
                RedisKeyConstant.USER_SIMPLE_PROFILE_TTL,
                () -> userProfileMapper.selectUserSimpleBOById(userId)
        );
        if (userSimpleBO == null) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND);
        }
        return userSimpleBO;
    }

    /**
     * 批量获取用户简要信息。
     *
     * <p>流程：
     * <ol>
     *   <li>multiGet 一次拿所有命中的缓存</li>
     *   <li>找出未命中的 userId，批量查 DB</li>
     *   <li>DB 结果 mset 批量回写缓存</li>
     *   <li>合并返回完整 Map</li>
     * </ol>
     *
     * @param userIds 用户 ID 集合（允许含重复，内部去重）
     * @return userId → UserSimpleVO 的 Map；查不到的用户不在 Map 中
     */
    @Override
    public Map<Long, UserSimpleBO> batchGetSimple(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) return Map.of();

        // 去重
        List<Long> distinctIds = userIds.stream().distinct().toList();

        // 1. 构建 key 列表，multiGet 一次取缓存
        List<String> keys = distinctIds.stream()
                .map(RedisKeyConstant::userSimpleProfile)
                .toList();

        Map<String, UserSimpleBO> cached = redisService.multiGet(keys, UserSimpleBO.class);

        // 2. 找出未命中的 userId
        Map<Long, UserSimpleBO> result = new HashMap<>(distinctIds.size());
        List<Long> missIds = new ArrayList<>();

        for (int i = 0; i < distinctIds.size(); i++) {
            Long userId = distinctIds.get(i);
            UserSimpleBO bo = cached.get(keys.get(i));
            if (bo != null) {
                result.put(userId, bo); // 缓存命中
            } else {
                missIds.add(userId);    // 未命中，需查 DB
            }
        }

        // 3. 批量查 DB（IN 查询，一次搞定）
        if (!missIds.isEmpty()) {
            List<UserSimpleBO> dbResults = userProfileMapper.selectSimpleBatch(missIds);

            // 4. 回写缓存 + 合并结果
            Map<String, Object> toCache = new HashMap<>(dbResults.size());
            for (UserSimpleBO bo : dbResults) {
                result.put(bo.getId(), bo);
                toCache.put(RedisKeyConstant.userSimpleProfile(bo.getId()), bo);
            }
            if (!toCache.isEmpty()) {
                redisService.multiSet(toCache, RedisKeyConstant.USER_SIMPLE_PROFILE_TTL);
            }
        }

        return result;
    }

    @Override
    public UserAuthBO getUserAuthInfo(Long userId) {
        if (userId == null) {
            throw new BusinessException(ResultCode.USER_ID_REQUIRED);
        }

        UserAuthBO userAuthBO = loadCachedValue(
                RedisKeyConstant.userAuthProfile(userId),
                UserAuthBO.class,
                RedisKeyConstant.USER_AUTH_PROFILE_TTL,
                () -> userProfileMapper.selectUserAuthBOByUserId(userId)
        );
        if (userAuthBO == null) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND);
        }
        return userAuthBO;
    }

    /**
     * 获取用户的微信小程序 openid。
     */
    @Override
    public String getMiniOpenid(Long userId) {
        String openid = userProfileMapper.selectMiniOpenid(userId);
        if (openid == null) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND);
        }
        return openid;
    }

    /**
     * 更新当前登录用户资料。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateCurrentProfile(UserProfileUpdateBO bo) {
        if (bo == null) {
            throw new BusinessException(ResultCode.INVALID_OPERATION, "请求参数不能为空");
        }

        Long userId = requireUserId();
        log.info("[UserService.updateCurrentProfile][开始] userId={}", userId);

        if (!hasAnyProfileUpdateField(bo)) {
            throw new BusinessException(ResultCode.USER_PROFILE_EMPTY);
        }

        UserProfile profile = userConverter.toEntity(bo);

        profile.setId(userId);
        int updated = userProfileMapper.updateById(profile);
        if (updated == 0) {
            log.warn("[UserService.updateCurrentProfile][用户不存在] userId={}", userId);
            throw new BusinessException(ResultCode.USER_NOT_FOUND);
        }

        evictUserProfileCache(userId);

        eventPublisher.publishEvent(new UserProfileUpdatedEvent(userId, bo));

        log.info("[UserService.updateCurrentProfile][完成] userId={}", userId);
    }

    /**
     * 当前登录用户同意协议。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void agreeCurrentAgreement(String version) {
        Long userId = requireUserId();
        log.info("[UserService.agreeCurrentAgreement][开始] userId={}, version={}", userId, version);
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
            userAgreement.setAgreedAt(LocalDateTime.now());
            userAgreementMapper.updateById(userAgreement);
        }

        redisService.delete(RedisKeyConstant.userAgreement(userId));
        log.info("[UserService.agreeCurrentAgreement][完成] userId={}, version={}", userId, version);
    }

    /**
     * 查询当前登录用户协议记录。
     */
    @Override
    public UserAgreement getCurrentAgreement() {
        Long userId = requireUserId();
        return loadCachedValue(
                RedisKeyConstant.userAgreement(userId),
                UserAgreement.class,
                RedisKeyConstant.USER_AGREEMENT_TTL,
                () -> userAgreementMapper.selectById(userId)
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

    @Override
    public List<Long> listAllUserIds(long lastId, int limit) {
        return userProfileMapper.selectObjs(
                new LambdaQueryWrapper<UserProfile>()
                        .select(UserProfile::getId)
                        .gt(lastId > 0, UserProfile::getId, lastId)
                        .orderByAsc(UserProfile::getId)
                        .last(" LIMIT " + limit)
        );
    }

    private boolean hasAnyProfileUpdateField(UserProfileUpdateBO bo) {
        return bo.getNickname() != null
                || bo.getAvatarUrl() != null
                || bo.getBackgroundUrl() != null
                || bo.getBio() != null
                || bo.getGender() != null;
    }

    private void evictUserProfileCache(Long userId) {
        redisService.delete(RedisKeyConstant.userPublicProfile(userId));
        redisService.delete(RedisKeyConstant.userSimpleProfile(userId));
    }
}
