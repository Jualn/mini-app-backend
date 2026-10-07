package cn.jualn.miniapp.module.setting.service.impl;

import cn.jualn.miniapp.common.constant.RedisKeyConstant;
import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.module.setting.bo.UserSettingBO;
import cn.jualn.miniapp.module.setting.converter.SettingConverter;
import cn.jualn.miniapp.module.setting.entity.UserSetting;
import cn.jualn.miniapp.module.setting.mapper.UserSettingMapper;
import cn.jualn.miniapp.module.setting.service.SettingService;
import cn.jualn.miniapp.module.notify.service.NotificationPreferenceBridgeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Slf4j
@Service
@RequiredArgsConstructor
public class SettingServiceImpl implements SettingService {

    private final UserSettingMapper userSettingMapper;
    private final RedisService redisService;
    private final SettingConverter settingConverter;
    private final NotificationPreferenceBridgeService preferenceBridgeService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserSettingBO getCurrentSetting() {
        Long userId = requireUserId();

        UserSettingBO setting = getSetting(userId);
        log.debug("[SettingService.getCurrentSetting][完成] userId={}", userId);
        return setting;
    }

    @Override
    public UserSettingBO getSettingByUserId(Long userId) {
        if (userId == null) {
            log.warn("[SettingService.getSettingByUserId][参数错误] userId=null");
            return null;
        }

        return getSetting(userId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateCurrentSetting(UserSettingBO bo) {
        Long userId = requireUserId();
        log.debug("[SettingService.updateCurrentSetting][开始] userId={}", userId);
        ensureSettingExists(userId);
        userSettingMapper.selectByIdForUpdate(userId);
        UserSetting setting = settingConverter.toEntity(bo);

        setting.setUserId(userId);
        if (preferenceBridgeService.isCanonicalOwner(userId)) {
            setting.setNotifyActivityRemind(null);
            setting.setNotifyExamRemind(null);
            preferenceBridgeService.updateOfficialAccount(userId,
                    bo.getNotifyActivityRemind(), bo.getNotifyExamRemind());
        }
        userSettingMapper.updateById(setting);
        invalidateSettingAfterCommit(userId);

        log.debug("[SettingService.updateCurrentSetting][完成] userId={}", userId);
    }

    private UserSettingBO getSetting(Long userId) {
        String settingsCacheKey = buildUserSettingCacheKey(userId);
        UserSettingBO cachedSetting = redisService.get(settingsCacheKey, UserSettingBO.class);
        if (cachedSetting != null) {
            log.debug("[SettingService.getSetting][从缓存加载] userId={}", userId);
            return overlayCanonicalOfficialAccount(userId, cachedSetting);
        }

        return overlayCanonicalOfficialAccount(userId, loadOrCreateSetting(userId));
    }

    private UserSettingBO loadOrCreateSetting(Long userId) {
        UserSetting setting = userSettingMapper.selectById(userId);
        UserSettingBO settingBO;
        if (setting == null) {
            ensureSettingExists(userId);
            setting = userSettingMapper.selectById(userId);
        }
        settingBO = settingConverter.toBO(setting);

        String settingsCacheKey = buildUserSettingCacheKey(userId);
        redisService.set(settingsCacheKey, settingBO, RedisKeyConstant.USER_SETTING_TTL);
        return settingBO;
    }

    private void ensureSettingExists(Long userId) {
        if (userSettingMapper.selectById(userId) != null) return;
        try {
            userSettingMapper.insert(UserSetting.builder().userId(userId).build());
        } catch (DuplicateKeyException e) {
            log.debug("[SettingService.ensureSettingExists][并发初始化] userId={}", userId);
        }
    }

    private UserSettingBO overlayCanonicalOfficialAccount(Long userId, UserSettingBO setting) {
        if (setting == null || !preferenceBridgeService.isCanonicalOwner(userId)) return setting;
        NotificationPreferenceBridgeService.OfficialAccountPreferences preferences =
                preferenceBridgeService.getOfficialAccount(userId);
        setting.setNotifyActivityRemind(preferences.activityEnabled());
        setting.setNotifyExamRemind(preferences.publicEventEnabled());
        return setting;
    }

    private void invalidateSettingAfterCommit(Long userId) {
        Runnable invalidation = () -> redisService.delete(buildUserSettingCacheKey(userId));
        if (TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    invalidation.run();
                }
            });
        } else {
            invalidation.run();
        }
    }

    private Long requireUserId() {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        return userId;
    }

    private String buildUserSettingCacheKey(Long userId) {
        return RedisKeyConstant.userSetting(userId);
    }
}
