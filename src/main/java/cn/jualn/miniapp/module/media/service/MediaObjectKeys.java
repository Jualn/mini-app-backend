package cn.jualn.miniapp.module.media.service;

import cn.jualn.miniapp.common.enums.TargetType;

/** Business namespaces share upload/retained stages; bucket selection is independent. */
public final class MediaObjectKeys {
    private MediaObjectKeys() {}

    public static String uploadPrefix(TargetType type, long owner) {
        return type.getKey() + "/upload/" + owner + "/";
    }

    public static String retainedPrefix(TargetType type, long owner) {
        return type.getKey() + "/retained/" + owner + "/";
    }

    public static boolean isCommunityRetained(String key) {
        return key != null && (key.startsWith("post/retained/")
                || key.startsWith("comment/retained/") || key.startsWith("growth-record/retained/"));
    }
}
