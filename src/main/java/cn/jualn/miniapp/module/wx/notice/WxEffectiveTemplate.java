package cn.jualn.miniapp.module.wx.notice;

import java.util.List;
import java.util.Map;

/** Immutable, application-owned configuration after legacy normalization and validation. */
public record WxEffectiveTemplate(String bindingRef, String notificationType, String channel,
        String accountRef, String product, String definitionRef, int bindingVersion,
        int definitionVersion, String templateId, boolean enabled, boolean subscribeVisible,
        String displayName, Map<String, Field> fields, String jumpTarget, String pagePath,
        String miniProgramState, String configDigest) {
    public WxEffectiveTemplate { fields = Map.copyOf(fields); }

    public record Field(String source, String formatter, String zone, String overflow,
            String defaultValue, boolean required, Integer maxLength, List<String> allowedValues) {
        public Field { allowedValues = List.copyOf(allowedValues); }
    }
}
