package cn.jualn.miniapp.module.wx.notice;

import cn.jualn.miniapp.module.notify.channel.NotificationTemplateStatus;
import cn.jualn.miniapp.module.notify.model.NotificationCategory;
import cn.jualn.miniapp.module.notify.enums.NotifyType;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.third.wx.config.WxProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** One detached, immutable first-release catalog. Invalid entries isolate their own type. */
@Component
public class WxNotificationTemplateRegistry {
    private final WxNotificationTemplateProperties properties;
    private final Map<String,WxEffectiveTemplate> templates = new LinkedHashMap<>();
    private final Map<String,NotificationTemplateStatus> states = new LinkedHashMap<>();
    private final String revision;
    @Autowired
    public WxNotificationTemplateRegistry(WxNotificationTemplateProperties configuration,WxProperties accounts) {
        properties = new ObjectMapper().convertValue(configuration,WxNotificationTemplateProperties.class);
        if (!StringUtils.hasText(properties.getEnvironment())) throw new IllegalArgumentException("Notification environment missing");
        for (var entry : properties.getBindings().entrySet()) {
            var binding=entry.getValue();
            if(binding==null || binding.getNotificationType()==null || binding.getChannel()==null) continue;
            String key=binding.getNotificationType()+":"+binding.getChannel();
            var definition=properties.getDefinitions().get(binding.getDefinitionRef());
            var issues=issues(binding,definition);
            if(states.containsKey(key)) {issues=List.of("MAPPING_INVALID");templates.values().removeIf(t->key.equals(t.notificationType()+":"+t.channel()));}
            if(!issues.isEmpty()) {states.put(key,new NotificationTemplateStatus("INVALID",issues,null,null,null));continue;}
            var fields=new LinkedHashMap<String,WxEffectiveTemplate.Field>();
            binding.getFields().forEach((name,mapping)->{
                var rule=definition.getFields().get(name);
                fields.put(name,new WxEffectiveTemplate.Field(mapping.getSource(),mapping.getFormatter(),mapping.getZone(),mapping.getOverflow(),
                        mapping.getDefaultValue(),rule.isRequired(),rule.getMaxLength(),rule.getAllowedValues()));
            });
            var template=new WxEffectiveTemplate(entry.getKey(),binding.getNotificationType(),binding.getChannel(),binding.getAccountRef(),
                    definition.getProduct(),binding.getDefinitionRef(),binding.getVersion(),definition.getVersion(),definition.getTemplateId(),true,true,
                    definition.getTitle(),fields,binding.getJumpTarget(),null,definition.getMiniProgramState(),
                    WxNotificationTemplateRenderer.configurationDigest(Map.of("definition",definition,"binding",binding)));
            templates.put(entry.getKey(),template);
            states.put(key,new NotificationTemplateStatus("READY",List.of(),definition.getTemplateId(),definition.getTitle(),definition.getSubscriptionKind()));
        }
        // One real template ID must have one native title and kind across all bindings.
        var identities=new HashMap<String,String>();
        var conflicts=new HashSet<String>();
        for(var state:states.values()) if("READY".equals(state.status())) {
            String value=state.title()+":"+state.subscriptionKind();
            String old=identities.putIfAbsent(state.templateId(),value);
            if(old!=null && !old.equals(value)) conflicts.add(state.templateId());
        }
        states.replaceAll((key,state)->conflicts.contains(state.templateId())
                ?new NotificationTemplateStatus("INVALID",List.of("TEMPLATE_INVALID"),null,null,null):state);
        templates.values().removeIf(t->!"READY".equals(states.get(t.notificationType()+":"+t.channel()).status()));
        String app=accounts==null || accounts.getMa()==null ? "" : String.valueOf(accounts.getMa().getAppId());
        revision=WxNotificationTemplateRenderer.configurationDigest(Map.of("catalog",properties,"account",app));
    }
    private List<String> issues(WxNotificationTemplateProperties.Binding binding,WxNotificationTemplateProperties.Definition definition) {
        if(definition==null || !StringUtils.hasText(definition.getTemplateId()) || !StringUtils.hasText(definition.getTitle()) || definition.getVersion()<1)
            return List.of("TEMPLATE_INVALID");
        if(!Set.of("ONE_TIME","LONG_TERM").contains(String.valueOf(definition.getSubscriptionKind()))) return List.of("SUBSCRIPTION_KIND_UNKNOWN");
        if(!"WECHAT_MINI_PROGRAM".equals(binding.getChannel()) || !binding.getChannel().equals(definition.getChannel())
                || !"ma-primary".equals(binding.getAccountRef()) || !binding.getAccountRef().equals(definition.getAccountRef())
                || !"MINI_PROGRAM_SUBSCRIBE".equals(definition.getProduct())) return List.of("ACCOUNT_MISMATCH");
        if(!Set.of("developer","trial","formal").contains(String.valueOf(definition.getMiniProgramState()))) return List.of("JUMP_INVALID");
        if(!WxNoticeFieldCatalog.knownType(binding.getNotificationType()) || binding.getVersion()<1 || binding.getFields()==null
                || definition.getFields()==null || binding.getFields().isEmpty() || !binding.getFields().keySet().equals(definition.getFields().keySet())) return List.of("MAPPING_INVALID");
        if(!new WxNotificationTargetResolver().supports(binding.getNotificationType(),binding.getJumpTarget())) return List.of("JUMP_INVALID");
        for(var entry:binding.getFields().entrySet()) {
            var m=entry.getValue();var f=definition.getFields().get(entry.getKey());
            if(m==null || f==null || !entry.getKey().matches("[a-zA-Z][a-zA-Z0-9_]{0,63}")
                    || !Set.of("TEXT","DATE","DATETIME","NUMBER").contains(String.valueOf(f.getDataType()))
                    || !WxNoticeFieldCatalog.allows(binding.getNotificationType(),m.getSource())
                    || !f.getDataType().toLowerCase(java.util.Locale.ROOT).equals(m.getFormatter())
                    || !Set.of("reject","truncate").contains(String.valueOf(m.getOverflow()))
                    || f.isRequired() && StringUtils.hasText(m.getDefaultValue())
                    || f.getAllowedValues()==null || f.getAllowedValues().stream().anyMatch(java.util.Objects::isNull)
                    || f.getMaxLength()!=null && f.getMaxLength()<=0
                    || !"TEXT".equals(f.getDataType()) && "truncate".equals(m.getOverflow())) return List.of("MAPPING_INVALID");
            try {java.time.ZoneId.of(m.getZone());} catch(RuntimeException invalid) {return List.of("MAPPING_INVALID");}
        }
        return List.of();
    }
    public String environment(){return properties.getEnvironment();}
    public String revision(){return revision;}
    public boolean isChannelEnabled(String channel){return "WECHAT_MINI_PROGRAM".equals(channel);}
    public NotificationTemplateStatus status(String type,String channel) {
        if("WECHAT_OFFICIAL_ACCOUNT".equals(channel)) return NotificationTemplateStatus.disabled();
        return states.getOrDefault(type+":"+channel,NotificationTemplateStatus.missing());
    }
    public WxEffectiveTemplate resolve(String type,String channel){return templates.values().stream().filter(t->t.notificationType().equals(type)&&t.channel().equals(channel)).findFirst().orElse(null);}
    public String bindingRef(String type,String channel){var template=resolve(type,channel);return template==null?null:template.bindingRef();}
    public WxEffectiveTemplate effective(String ref){var template=templates.get(ref);if(template==null)throw new BusinessException(ResultCode.WX_NOTICE_TEMPLATE_UNAVAILABLE);return template;}
    public List<WxMpNoticeTemplateView> listSubscribeVisibleTemplates(){return List.of();}
    public boolean isSnapshotEnabled(WxDeliveryTemplateSnapshot snapshot){
        if(snapshot==null || !isChannelEnabled(snapshot.channel()))return false;
        var current=templates.get(snapshot.bindingRef());var original=properties.getDefinitions().get(snapshot.definitionRef());
        return current!=null && original!=null && StringUtils.hasText(original.getTemplateId())
                && snapshot.channel().equals(current.channel()) && snapshot.accountRef().equals(current.accountRef())
                && snapshot.product().equals(current.product()) && original.getChannel().equals(snapshot.channel())
                && original.getAccountRef().equals(snapshot.accountRef()) && original.getProduct().equals(snapshot.product());
    }
}
