package cn.jualn.miniapp.module.wx.notice;

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
 * 微信通知的应用配置目录；启动时将兼容配置与显式绑定归一化。
 * <p>
 * 统一提供模板 ID、展示名称、是否可订阅、是否可发送等信息。
 * </p>
 */
@Component
public class WxNotificationTemplateRegistry {

    private final WxMpNoticeTemplateProperties properties;
    private final Map<String, WxMpNoticeTemplateProperties.Template> templatesByType;
    private final WxNotificationTemplateProperties notificationProperties;
    private final Map<String, WxNotificationTemplateProperties.Binding> bindings;
    private final Map<String, WxEffectiveTemplate> effectiveTemplates;

    public WxNotificationTemplateRegistry(WxMpNoticeTemplateProperties properties) {
        this(properties, new WxNotificationTemplateProperties());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public WxNotificationTemplateRegistry(WxMpNoticeTemplateProperties properties, WxNotificationTemplateProperties notificationProperties) {
        this.properties = new com.fasterxml.jackson.databind.ObjectMapper()
                .convertValue(properties, WxMpNoticeTemplateProperties.class);
        this.templatesByType = validateAndIndex(this.properties);
        // Take a detached copy: mutating a bound bean must not hot-update a running Registry.
        this.notificationProperties = new com.fasterxml.jackson.databind.ObjectMapper()
                .convertValue(notificationProperties, WxNotificationTemplateProperties.class);
        this.bindings = validateBindings();
        this.effectiveTemplates = normalize();
    }

    /**
     * 获取 H5 订阅页可展示的模板。
     */
    public List<WxMpNoticeTemplateView> listSubscribeVisibleTemplates() {
        if (!isChannelEnabled("WECHAT_OFFICIAL_ACCOUNT")) return List.of();
        Map<String, WxMpNoticeTemplateView> visible = new LinkedHashMap<>();
        effectiveTemplates.values().stream().sorted(java.util.Comparator.comparing(WxEffectiveTemplate::bindingRef))
                .filter(t -> "WECHAT_OFFICIAL_ACCOUNT".equals(t.channel()) && t.enabled() && t.subscribeVisible())
                .filter(t -> resolve(t.notificationType(), t.channel()) == t)
                .forEach(t -> visible.put(t.templateId(), WxMpNoticeTemplateView.builder()
                        .type(t.notificationType()).name(t.displayName()).templateId(t.templateId()).build()));
        return List.copyOf(visible.values());
    }

    public boolean isChannelEnabled(String channel) {
        if ("WECHAT_MINI_PROGRAM".equals(channel)) return notificationProperties.getMiniProgram().isEnabled();
        if ("WECHAT_OFFICIAL_ACCOUNT".equals(channel)) return notificationProperties.getOfficialAccount().isEnabled();
        return false;
    }

    public WxEffectiveTemplate effective(String ref) {
        var template = effectiveTemplates.get(ref);
        if (template == null) throw new BusinessException(ResultCode.WX_NOTICE_TEMPLATE_UNAVAILABLE, "微信模板未配置");
        return template;
    }

    /** One resolution policy for subscription entries, capabilities, planning and sending. */
    public WxEffectiveTemplate resolve(String type, String channel) {
        if (type == null || channel == null) return null;
        String ref = bindingRef(type, channel);
        if (ref != null) return effectiveTemplates.get(ref); // Disabled explicit bindings also shadow fallback.
        if (!"WECHAT_OFFICIAL_ACCOUNT".equals(channel)) return null;
        String legacy = switch (type) {
            case "COMMENTED_ME", "POST_COMMENTED" -> "comment_reply";
            case "REPLIED_ME", "COMMENT_REPLIED" -> "reply";
            case "LIKED_ME" -> "like";
            case "ACTIVITY_REMIND", "ACTIVITY_START_REMINDER" -> "activity_start";
            case "EXAM_REMIND" -> "exam_remind";
            case "AUDIT_RESULT" -> "audit_result";
            case "SYSTEM" -> "system_notice";
            default -> type;
        };
        ref = bindingRef(legacy, channel);
        return effectiveTemplates.get(ref == null ? "legacy:" + legacy : ref);
    }

    private Map<String, WxEffectiveTemplate> normalize() {
        Map<String, WxEffectiveTemplate> result = new LinkedHashMap<>();
        templatesByType.forEach((type, template) -> {
            Map<String, WxEffectiveTemplate.Field> fields = new LinkedHashMap<>();
            if (Boolean.TRUE.equals(template.getEnabled()) && template.getFields() != null) template.getFields().forEach((key, field) -> fields.put(key,
                    new WxEffectiveTemplate.Field(field.getSource(), field.getFormatter().toLowerCase(Locale.ROOT),
                            "Asia/Shanghai", "truncate", field.getDefaultValue(), Boolean.TRUE.equals(field.getRequired()),
                            field.getMaxLength(), List.of())));
            result.put("legacy:" + type, new WxEffectiveTemplate("legacy:" + type, type,
                    "WECHAT_OFFICIAL_ACCOUNT", "mp-primary", "OFFICIAL_ACCOUNT_SUBSCRIBE_BIZSEND", "legacy:" + type,
                    template.getVersion(), template.getVersion(), template.getTemplateId(), Boolean.TRUE.equals(template.getEnabled()),
                    Boolean.TRUE.equals(template.getSubscribeVisible()), template.getName(), fields, null, template.getPagePath(),
                    null, WxNotificationTemplateRenderer.configurationDigest(template)));
        });
        bindings.forEach((ref, binding) -> {
            var definition = notificationProperties.getDefinitions().get(binding.getDefinitionRef());
            Map<String, WxEffectiveTemplate.Field> fields = new LinkedHashMap<>();
            binding.getFields().forEach((key, mapping) -> {
                var rule = definition.getFields().get(key);
                fields.put(key, new WxEffectiveTemplate.Field(mapping.getSource(), mapping.getFormatter(), mapping.getZone(),
                        mapping.getOverflow(), mapping.getDefaultValue(), rule.isRequired(), rule.getMaxLength(), rule.getAllowedValues()));
            });
            result.put(ref, new WxEffectiveTemplate(ref, binding.getNotificationType(), binding.getChannel(), binding.getAccountRef(),
                    definition.getProduct(), binding.getDefinitionRef(), binding.getVersion(), definition.getVersion(), definition.getTemplateId(),
                    binding.isEnabled() && definition.isEnabled(), binding.isSubscribeVisible(),
                    StringUtils.hasText(binding.getDisplayName()) ? binding.getDisplayName() : displayName(binding.getNotificationType()),
                    fields, binding.getJumpTarget(), null, definition.getMiniProgramState(),
                    WxNotificationTemplateRenderer.configurationDigest(Map.of("definition", definition, "binding", binding))));
        });
        return Map.copyOf(result);
    }

    private String displayName(String type) {
        for (var value : cn.jualn.miniapp.module.notify.enums.NotifyType.values())
            if (value.name().equals(type)) return value.getDesc();
        return WxMpNoticeType.fromKey(type).getName();
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
            if (template.getVersion() < 1) throw invalid(configKey, "version 必须大于 0");
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
        return new IllegalStateException("微信通知配置 " + configKey + " 无效：" + reason);
    }

    public String environment() { return notificationProperties.getEnvironment(); }

    public String bindingRef(String type, String channel) {
        return bindings.entrySet().stream().filter(e -> type.equals(e.getValue().getNotificationType())
                && channel.equals(e.getValue().getChannel())).map(Map.Entry::getKey).findFirst().orElse(null);
    }

    /** Current disable/account/product gate only; versions/content come from the durable snapshot. */
    public boolean isSnapshotEnabled(WxDeliveryTemplateSnapshot snapshot) {
        if (snapshot == null || snapshot.bindingRef() == null) return false;
        var current = effectiveTemplates.get(snapshot.bindingRef());
        if (current == null || !current.enabled() || !current.channel().equals(snapshot.channel())
                || !current.accountRef().equals(snapshot.accountRef()) || !current.product().equals(snapshot.product())) return false;
        if (snapshot.bindingRef().startsWith("legacy:")) return true;
        var definition = notificationProperties.getDefinitions().get(snapshot.definitionRef());
        return definition != null && definition.isEnabled()
                && definition.getChannel().equals(snapshot.channel()) && definition.getAccountRef().equals(snapshot.accountRef())
                && definition.getProduct().equals(snapshot.product());
    }

    private Map<String, WxNotificationTemplateProperties.Binding> validateBindings() {
        if (!StringUtils.hasText(environment()) || !environment().matches("[a-zA-Z0-9_-]{1,32}")) throw invalid("environment", "环境标识无效");
        var result = new LinkedHashMap<String, WxNotificationTemplateProperties.Binding>();
        var identities = new java.util.HashSet<String>();
        notificationProperties.getDefinitions().forEach((ref, definition) -> {
            if (!ref.matches("[a-zA-Z0-9_-]{1,64}") || definition == null || definition.getVersion() < 1) throw invalid(ref, "定义/版本无效");
            boolean official = "WECHAT_OFFICIAL_ACCOUNT".equals(definition.getChannel());
            boolean mini = "WECHAT_MINI_PROGRAM".equals(definition.getChannel());
            if ((!official && !mini) || !(official ? "mp-primary" : "ma-primary").equals(definition.getAccountRef())
                    || !(official ? "OFFICIAL_ACCOUNT_SUBSCRIBE_BIZSEND" : "MINI_PROGRAM_SUBSCRIBE").equals(definition.getProduct()))
                throw invalid(ref, "渠道、固定账号引用和消息产品不匹配");
            if (!StringUtils.hasText(definition.getTemplateId()) || definition.getFields().isEmpty()) throw invalid(ref, "模板 ID/字段缺失");
            if (definition.isEnabled() && !StringUtils.hasText(definition.getEvidenceRef())) throw invalid(ref, "启用定义须有人工核验依据引用");
            if (mini && (!StringUtils.hasText(definition.getMiniProgramState())
                    || !Set.of("developer", "trial", "formal").contains(definition.getMiniProgramState())))
                throw invalid(ref, "小程序跳转环境无效");
            definition.getFields().forEach((key, field) -> {
                if (!key.matches("[a-zA-Z][a-zA-Z0-9_]{0,63}") || field == null
                        || !Set.of("TEXT", "NUMBER", "DATE", "DATETIME").contains(field.getDataType())
                        || (field.getMaxLength() != null && field.getMaxLength() <= 0)) throw invalid(ref, "字段定义无效");
            });
        });
        notificationProperties.getBindings().forEach((ref, binding) -> {
            if (!ref.matches("[a-zA-Z0-9_-]{1,64}") || binding == null || binding.getVersion() < 1
                    || !WxNoticeFieldCatalog.knownType(binding.getNotificationType())) throw invalid(ref, "业务类型/版本无效");
            var definition = notificationProperties.getDefinitions().get(binding.getDefinitionRef());
            if (definition == null || !definition.getChannel().equals(binding.getChannel())
                    || !definition.getAccountRef().equals(binding.getAccountRef())) throw invalid(ref, "绑定跨渠道/账号或引用缺失");
            if (!identities.add(binding.getNotificationType() + ":" + binding.getChannel() + ":" + binding.getAccountRef())) throw invalid(ref, "重复绑定");
            if (!Set.of("ACTIVITY_DETAIL", "PUBLIC_EVENT_DETAIL", "INBOX").contains(binding.getJumpTarget())) throw invalid(ref, "跳转规则未注册");
            if (binding.getNotificationType().startsWith("ACTIVITY_") && !"ACTIVITY_DETAIL".equals(binding.getJumpTarget())
                    || binding.getNotificationType().startsWith("PUBLIC_EVENT_") && !"PUBLIC_EVENT_DETAIL".equals(binding.getJumpTarget())) throw invalid(ref, "业务目标不匹配");
            if (!binding.getFields().keySet().equals(definition.getFields().keySet())) throw invalid(ref, "字段映射不完整");
            binding.getFields().forEach((key, mapping) -> {
                if (mapping == null || !WxNoticeFieldCatalog.allows(binding.getNotificationType(), mapping.getSource())
                        || !Set.of("text", "date", "datetime", "number").contains(mapping.getFormatter())
                        || !Set.of("reject", "truncate").contains(mapping.getOverflow())) throw invalid(ref, "字段来源/转换未注册");
                try { java.time.ZoneId.of(mapping.getZone()); } catch (RuntimeException badZone) { throw invalid(ref, "时区无效"); }
                var rule = definition.getFields().get(key);
                String requiredFormatter = rule.getDataType().toLowerCase(Locale.ROOT);
                if (!requiredFormatter.equals(mapping.getFormatter()) || rule.isRequired() && StringUtils.hasText(mapping.getDefaultValue())
                        || !"TEXT".equals(rule.getDataType()) && "truncate".equals(mapping.getOverflow())) throw invalid(ref, "字段类型/默认值/截断策略不匹配");
            });
            result.put(ref, binding);
        });
        return Map.copyOf(result);
    }
}
