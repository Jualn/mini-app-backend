package cn.jualn.miniapp.module.post.converter;

import cn.jualn.miniapp.module.post.enums.PostStatus;

/**
 * Pure conversion of post-owned codes; contains no business decisions or persistence access.
 */
public final class PostEnumMappings {
    private PostEnumMappings() {
    }

    public static PostStatus toPostStatus(Integer code) {
        if (code == null) return null;
        return PostStatus.fromCode(code);
    }

    public static Integer fromPostStatus(PostStatus status) {
        return status == null ? null : status.getCode();
    }
}
