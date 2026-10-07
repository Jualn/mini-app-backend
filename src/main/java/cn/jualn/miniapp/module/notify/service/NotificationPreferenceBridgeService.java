package cn.jualn.miniapp.module.notify.service;

public interface NotificationPreferenceBridgeService {
    boolean isCanonicalOwner(long userId);

    OfficialAccountPreferences getOfficialAccount(long userId);

    void updateOfficialAccount(long userId, Boolean activityEnabled, Boolean publicEventEnabled);

    boolean importLegacyAndSwitch(long userId, boolean activityEnabled, boolean publicEventEnabled);

    void initializeCanonicalOwner(long userId);

    record OfficialAccountPreferences(boolean activityEnabled, boolean publicEventEnabled) {}
}
