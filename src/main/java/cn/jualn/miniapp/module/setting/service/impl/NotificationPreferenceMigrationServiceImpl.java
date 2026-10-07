package cn.jualn.miniapp.module.setting.service.impl;

import cn.jualn.miniapp.module.notify.service.NotificationPreferenceBridgeService;
import cn.jualn.miniapp.module.setting.entity.UserSetting;
import cn.jualn.miniapp.module.setting.mapper.UserSettingMapper;
import cn.jualn.miniapp.module.setting.service.NotificationPreferenceMigrationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class NotificationPreferenceMigrationServiceImpl implements NotificationPreferenceMigrationService {
    private final UserSettingMapper settingMapper;
    private final NotificationPreferenceBridgeService preferenceBridgeService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean migrateUser(long userId) {
        UserSetting legacy = settingMapper.selectByIdForUpdate(userId);
        if (legacy == null) {
            throw new IllegalStateException("user_setting must exist before notification preference migration");
        }
        return preferenceBridgeService.importLegacyAndSwitch(userId,
                Boolean.TRUE.equals(legacy.getNotifyActivityRemind()),
                Boolean.TRUE.equals(legacy.getNotifyExamRemind()));
    }
}
