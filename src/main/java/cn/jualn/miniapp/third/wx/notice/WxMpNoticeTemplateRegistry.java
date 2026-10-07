package cn.jualn.miniapp.third.wx.notice;

import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.ResultCode;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 服务号通知模板注册表。
 * <p>
 * 统一提供模板 ID、展示名称、是否可订阅、是否可发送等信息。
 * </p>
 */
@Component
public class WxMpNoticeTemplateRegistry {

    private final WxMpNoticeTemplateProperties properties;
    private final Map<String, WxMpNoticeTemplateProperties.Template> templatesByType;

    public WxMpNoticeTemplateRegistry(WxMpNoticeTemplateProperties properties) {
        this.properties = properties;
        this.templatesByType = validateAndIndex(properties);
    }

    /**
     * 获取指定通知类型的模板配置。
     */
    public WxMpNoticeTemplateProperties.Template getRequired(WxMpNoticeType type) {
        WxMpNoticeTemplateProperties.Template template = templatesByType.get(type.getKey());
        if (template == null) {
            throw new BusinessException(
                    ResultCode.WX_NOTICE_TEMPLATE_UNAVAILABLE,
                    "未配置服务号通知模板：" + type.getKey()
            );
        }
        return template;
    }

    /**
     * Configuration readiness only; subscription permission remains provider-owned.
     */
    public boolean isSendEnabled(WxMpNoticeType type) {
        if (type == null) return false;
        WxMpNoticeTemplateProperties.Template template = templatesByType.get(type.getKey());
        return template != null && Boolean.TRUE.equals(template.getEnabled())
                && StringUtils.hasText(template.getTemplateId());
    }

    /**
     * 获取 H5 订阅页可展示的模板。
     */
    public List<WxMpNoticeTemplateView> listSubscribeVisibleTemplates() {
        return properties.getNoticeTemplates()
                .values()
                .stream()
                .filter(item -> Boolean.TRUE.equals(item.getEnabled()))
                .filter(item -> Boolean.TRUE.equals(item.getSubscribeVisible()))
                .filter(item -> StringUtils.hasText(item.getTemplateId()))
                .map(item -> WxMpNoticeTemplateView.builder()
                        .type(item.getType())
                        .name(item.getName())
                        .templateId(item.getTemplateId())
                        .build())
                .toList();
    }

    private Map<String, WxMpNoticeTemplateProperties.Template> validateAndIndex(
            WxMpNoticeTemplateProperties source) {
        Map<String, WxMpNoticeTemplateProperties.Template> result = new LinkedHashMap<>();
        source.getNoticeTemplates().forEach((configKey, template) -> {
            if (template == null || !StringUtils.hasText(template.getType())) {
                throw invalid(configKey, "type 不能为空");
            }
            try {
                WxMpNoticeType.fromKey(template.getType());
            } catch (IllegalArgumentException unknownType) {
                throw invalid(configKey, "type 未在服务号通知类型中注册");
            }
            if (result.putIfAbsent(template.getType(), template) != null) {
                throw invalid(configKey, "type 重复：" + template.getType());
            }
            if (!Boolean.TRUE.equals(template.getEnabled())) {
                return;
            }
            if (!StringUtils.hasText(template.getTemplateId())) {
                throw invalid(configKey, "启用模板必须配置 template-id");
            }
            String path = template.getPagePath();
            if (StringUtils.hasText(path) && (path.startsWith("//") || path.contains(":")
                    || path.contains("\\") || path.contains("#") || path.chars().anyMatch(Character::isWhitespace))) {
                throw invalid(configKey, "page-path 必须是小程序站内路径");
            }
            if (template.getFields() == null || template.getFields().isEmpty()) {
                throw invalid(configKey, "启用模板必须配置 fields");
            }
            template.getFields().forEach((fieldKey, field) -> validateField(configKey, fieldKey, field));
        });
        return Map.copyOf(result);
    }

    private void validateField(String configKey, String fieldKey, WxMpNoticeTemplateProperties.Field field) {
        if (!StringUtils.hasText(fieldKey) || field == null || !StringUtils.hasText(field.getSource())) {
            throw invalid(configKey, "字段 key/source 不能为空");
        }
        if (field.getMaxLength() != null && field.getMaxLength() <= 0) {
            throw invalid(configKey, "字段 max-length 必须大于 0：" + fieldKey);
        }
        if (Boolean.TRUE.equals(field.getRequired()) && StringUtils.hasText(field.getDefaultValue())) {
            throw invalid(configKey, "必填字段不能配置 default-value：" + fieldKey);
        }
        String formatter = field.getFormatter();
        if (!Set.of("text", "datetime", "date").contains(formatter == null ? "" : formatter.toLowerCase(Locale.ROOT))) {
            throw invalid(configKey, "字段 formatter 不支持：" + fieldKey);
        }
        if (!"text".equalsIgnoreCase(formatter) && field.getMaxLength() != null) {
            throw invalid(configKey, "时间字段不能配置截断长度：" + fieldKey);
        }
    }

    private IllegalStateException invalid(String configKey, String reason) {
        return new IllegalStateException("wx.mp.notice-templates." + configKey + " 配置无效：" + reason);
    }
}
