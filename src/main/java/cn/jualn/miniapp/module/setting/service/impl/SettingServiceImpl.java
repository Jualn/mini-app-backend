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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class SettingServiceImpl implements SettingService {

    private final UserSettingMapper userSettingMapper;
    private final RedisService redisService;
    private final SettingConverter settingConverter;

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
        log.info("[SettingService.updateCurrentSetting][开始] userId={}", userId);
        UserSetting setting = settingConverter.toEntity(bo);

        setting.setUserId(userId);
        userSettingMapper.updateById(setting);

        log.info("[SettingService.updateCurrentSetting][完成] userId={}", userId);
    }

    private UserSettingBO getSetting(Long userId) {
        String settingsCacheKey = buildUserSettingCacheKey(userId);
        UserSettingBO cachedSetting = redisService.get(settingsCacheKey, UserSettingBO.class);
        if (cachedSetting != null) {
            log.debug("[SettingService.getSetting][从缓存加载] userId={}", userId);
            return cachedSetting;
        }

        return loadOrCreateSetting(userId);
    }

    private UserSettingBO loadOrCreateSetting(Long userId) {
        UserSetting setting = userSettingMapper.selectById(userId);
        UserSettingBO settingBO;
        if (setting == null) {

            setting = UserSetting.builder().userId(userId).build();

            try {
                userSettingMapper.insert(setting);
            } catch (DuplicateKeyException e) {
                // 并发下可能有其他请求已初始化同一 userId，这里回查即可。
                log.debug("[SettingService.loadOrCreateSetting][并发初始化] userId={}", userId);
            }

            setting = userSettingMapper.selectById(userId);
        }
        settingBO = settingConverter.toBO(setting);

        String settingsCacheKey = buildUserSettingCacheKey(userId);
        redisService.set(settingsCacheKey, settingBO, RedisKeyConstant.USER_SETTING_TTL);
        return settingBO;
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
