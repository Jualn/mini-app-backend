package cn.jualn.miniapp.module.setting.service;

/** Controlled per-user migration entry; callers must not turn this into a read-side effect. */
public interface NotificationPreferenceMigrationService {
    boolean migrateUser(long userId);
}
