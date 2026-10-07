package cn.jualn.miniapp.module.notify.service.impl;

import cn.jualn.miniapp.module.notify.entity.NotificationPreference;
import cn.jualn.miniapp.module.notify.mapper.NotificationPreferenceMapper;
import cn.jualn.miniapp.module.notify.mapper.NotificationPreferenceOwnerMapper;
import cn.jualn.miniapp.module.notify.model.NotificationCategory;
import cn.jualn.miniapp.module.notify.model.NotificationChannel;
import cn.jualn.miniapp.module.notify.service.NotificationPreferenceBridgeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class NotificationPreferenceBridgeServiceImpl implements NotificationPreferenceBridgeService {
    private static final String OFFICIAL_ACCOUNT = NotificationChannel.WECHAT_OFFICIAL_ACCOUNT.name();
    private static final String USER_OVERRIDE = "USER_OVERRIDE";
    private static final String LEGACY_MIGRATION = "LEGACY_MIGRATION";

    private final NotificationPreferenceMapper preferenceMapper;
    private final NotificationPreferenceOwnerMapper ownerMapper;

    @Override
    public boolean isCanonicalOwner(long userId) {
        return ownerMapper.isCanonical(userId) > 0;
    }

    @Override
    public OfficialAccountPreferences getOfficialAccount(long userId) {
        Map<String, NotificationPreference> values = new HashMap<>();
        for (NotificationPreference preference : preferenceMapper.selectByUserId(userId)) {
            if (OFFICIAL_ACCOUNT.equals(preference.getChannel())) values.put(preference.getCategory(), preference);
        }
        return new OfficialAccountPreferences(enabled(values.get(NotificationCategory.ACTIVITY.name())),
                enabled(values.get(NotificationCategory.PUBLIC_EVENT.name())));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void updateOfficialAccount(long userId, Boolean activityEnabled, Boolean publicEventEnabled) {
        if (activityEnabled != null) upsert(userId, NotificationCategory.ACTIVITY, activityEnabled, USER_OVERRIDE);
        if (publicEventEnabled != null) upsert(userId, NotificationCategory.PUBLIC_EVENT,
                publicEventEnabled, USER_OVERRIDE);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean importLegacyAndSwitch(long userId, boolean activityEnabled, boolean publicEventEnabled) {
        if (isCanonicalOwner(userId)) return false;
        if (preferenceMapper.countByUserId(userId) > 0) {
            throw new IllegalStateException("notification preferences already exist before owner migration");
        }
        insert(userId, NotificationCategory.ACTIVITY, activityEnabled, LEGACY_MIGRATION);
        insert(userId, NotificationCategory.PUBLIC_EVENT, publicEventEnabled, LEGACY_MIGRATION);
        ownerMapper.insertCanonical(userId);
        return true;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void initializeCanonicalOwner(long userId) {
        if (!isCanonicalOwner(userId)) ownerMapper.insertCanonical(userId);
    }

    private void upsert(long userId, NotificationCategory category, boolean enabled, String source) {
        preferenceMapper.upsert(userId, category.name(), OFFICIAL_ACCOUNT, enabled, source);
    }

    private void insert(long userId, NotificationCategory category, boolean enabled, String source) {
        preferenceMapper.insert(userId, category.name(), OFFICIAL_ACCOUNT, enabled, source);
    }

    private boolean enabled(NotificationPreference preference) {
        return preference != null && Boolean.TRUE.equals(preference.getEnabled());
    }
}
