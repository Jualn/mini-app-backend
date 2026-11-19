package cn.jualn.miniapp.module.wx.notice;

import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.ResultCode;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;

/**
 * 微信通知应用映射；消费有效模板，冻结渲染内容和应用跳转。
 * <p>
 * 根据模板字段映射配置，将业务 payload 转换成微信模板 data。
 */
@Component
public class WxNotificationTemplateRenderer {

    public record Preview(boolean valid, WxDeliveryTemplateSnapshot snapshot, List<String> errors) { }

    /** Pure offline preview: no identity lookup, token, provider client or business queries. */
    public Preview preview(WxNotificationTemplateRegistry registry, String bindingRef, Map<String, Object> frozen) {
        try { return new Preview(true, renderBinding(registry, bindingRef, frozen), List.of()); }
        catch (BusinessException invalid) { return new Preview(false, null, List.of(invalid.getMessage())); }
        catch (RuntimeException invalid) { return new Preview(false, null, List.of("TEMPLATE_INPUT_INVALID")); }
    }

    public WxDeliveryTemplateSnapshot renderBinding(WxNotificationTemplateRegistry registry, String ref, Map<String, Object> frozen) {
        return renderTemplate(registry.environment(), registry.effective(ref), frozen);
    }

    public WxDeliveryTemplateSnapshot renderTemplate(String environment, WxEffectiveTemplate template, Map<String, Object> frozen) {
        Map<String, String> data = renderData(template, frozen);
        String path = new WxNotificationTargetResolver().resolve(template, frozen);
        return new WxDeliveryTemplateSnapshot(1, template.channel(), template.accountRef(), environment, null,
                template.product(), template.bindingRef(), template.bindingVersion(), template.definitionRef(), template.definitionVersion(),
                template.templateId(), data, path, null, template.configDigest(),
                "WECHAT_MINI_PROGRAM".equals(template.channel()) ? template.miniProgramState() : null);
    }

    public Map<String, String> renderData(WxEffectiveTemplate template, Map<String, Object> frozen) {
        Map<String, String> data = new LinkedHashMap<>();
        template.fields().forEach((key, field) -> {
            Object raw = template.bindingRef().startsWith("legacy:") ? frozen.get(field.source()) : WxNoticeFieldCatalog.read(frozen, field.source());
            if (raw == null || raw instanceof String text && !StringUtils.hasText(text)) raw = field.defaultValue();
            String value;
            try { value = cn.jualn.miniapp.third.wx.notice.WxTemplateValueFormatter.format(raw, field.formatter(), "Asia/Shanghai", field.zone()); }
            catch (RuntimeException invalid) { throw fieldInvalid(key, "TYPE_OR_FORMAT"); }
            if (!StringUtils.hasText(value) && field.required()) throw fieldInvalid(key, "REQUIRED");
            if (value == null) value = "";
            if (field.maxLength() != null && value.codePointCount(0, value.length()) > field.maxLength()) {
                if (!"truncate".equals(field.overflow())) throw fieldInvalid(key, "LENGTH");
                value = limitLength(value, field.maxLength());
            }
            if (!field.allowedValues().isEmpty() && !field.allowedValues().contains(value)) throw fieldInvalid(key, "ALLOWED_VALUES");
            data.put(key, value);
        });
        return Map.copyOf(data);
    }

    public String originatingPath(String path, Map<String, Object> frozen) {
        return new WxNotificationTargetResolver().originatingPath(path, frozen);
    }

    public static String digest(String content) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(content.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    public static String configurationDigest(Object config) {
        try {
            var json = new com.fasterxml.jackson.databind.ObjectMapper()
                    .configure(com.fasterxml.jackson.databind.MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                    .configure(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
            return digest(json.writeValueAsString(config));
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw new IllegalStateException("Template configuration digest failed");
        }
    }

    private BusinessException fieldInvalid(String key, String reason) {
        return new BusinessException(ResultCode.WX_NOTICE_PAYLOAD_INVALID, "FIELD:" + key + ":" + reason);
    }

    public String resolvePath(String template, Map<String, Object> frozen) {
        return new WxNotificationTargetResolver().resolvePath(template, frozen);
    }

    /**
     * 按配置裁剪长度，避免微信字段超长。
     */
    private String limitLength(String value, Integer maxLength) {
        if (!StringUtils.hasText(value) || maxLength == null || maxLength <= 0) {
            return value;
        }

        if (value.codePointCount(0, value.length()) <= maxLength) {
            return value;
        }

        return value.substring(0, value.offsetByCodePoints(0, maxLength));
    }
}
