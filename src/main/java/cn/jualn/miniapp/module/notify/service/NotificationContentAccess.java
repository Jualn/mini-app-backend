package cn.jualn.miniapp.module.notify.service;

/**
 * Current owner decision for historical notification snapshots.
 */
public interface NotificationContentAccess {
    record Decision(boolean growth, boolean allowed, String postId) {
    }

    Decision evaluate(long recipient, Integer targetType, Long targetId);
}
