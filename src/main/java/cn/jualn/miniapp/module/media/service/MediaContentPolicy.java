package cn.jualn.miniapp.module.media.service;

import cn.jualn.miniapp.common.enums.TargetType;

/** Implemented by the subject owner. Storage code does not inspect business rows. */
public interface MediaContentPolicy {
    TargetType targetType();
    boolean mayRead(long targetId, String objectKey, Long actor);
    boolean mayReadAdmin(long targetId, String objectKey, long operator);
}
