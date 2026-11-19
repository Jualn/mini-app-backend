package cn.jualn.miniapp.module.media.converter;

import cn.jualn.miniapp.module.media.enums.MediaType;

/** Pure conversion of media-owned codes; contains no business decisions or persistence access. */
public final class MediaEnumMappings {
    private MediaEnumMappings() { }

    public static MediaType toMediaType(Integer code) {
        if (code == null) return null;
        return MediaType.fromCode(code);
    }
    public static Integer fromMediaType(MediaType mediaType) {
        return mediaType == null ? null : mediaType.getCode();
    }
}
