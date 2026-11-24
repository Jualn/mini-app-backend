package cn.jualn.miniapp.module.notify.service.capability;
import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.module.notify.bo.NotificationCatalogBO;
import cn.jualn.miniapp.module.notify.channel.NotificationChannels;
import cn.jualn.miniapp.module.notify.model.NotificationCategory;
import cn.jualn.miniapp.module.notify.model.NotificationChannel;
import org.springframework.stereotype.Service;
import cn.jualn.miniapp.module.notify.enums.NotifyType;
import cn.jualn.miniapp.module.notify.model.NotificationSubscriptionScene;
import cn.jualn.miniapp.module.notify.service.NotificationSubscriptionContext;
import cn.jualn.miniapp.module.notify.channel.NotificationTemplateStatus;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.time.OffsetDateTime;
import java.time.ZoneId;
@Service
public class NotificationCatalogQueries {
    private final NotificationChannels channels;
    private final List<NotificationSubscriptionContext> contexts;
    @Autowired
    public NotificationCatalogQueries(NotificationChannels channels, List<NotificationSubscriptionContext> contexts) {
        this.channels=channels; this.contexts=List.copyOf(contexts);
    }
    public NotificationCatalogQueries(NotificationChannels channels) { this(channels,List.of()); }
    public NotificationCatalogBO.Options options(NotificationCategory category) { return options(category,null,null); }
    public NotificationCatalogBO.Options options(NotificationCategory category,
            NotificationSubscriptionScene scene, Long resourceId){
        if(UserContext.getUserId()==null)throw new BusinessException(ResultCode.UNAUTHORIZED);
        if (scene != null && category != null || (scene == null || !scene.resourceRequired()) && resourceId != null
                || scene != null && scene.resourceRequired() && resourceId == null || resourceId != null && resourceId <= 0)
            throw ContractProblemException.validation(new ContractProblemException.Violation("query","sceneCode","INVALID","订阅场景与上下文不匹配"));
        try {
            var selected = scene == null ? NotificationCategory.types() : scene.resourceRequired()
                    ? contexts.stream().filter(owner -> owner.supports(scene)).findFirst().orElseThrow(
                        () -> new IllegalStateException("Subscription scene owner missing")).types(scene,resourceId)
                    : scene.types();
            Map<String,List<NotifyType>> byId = scene == null ? new TreeMap<>() : new LinkedHashMap<>();
            var metadataById=new TreeMap<String,NotificationTemplateStatus>();
            for(var type:selected){
                if(category!=null && NotificationCategory.of(type)!=category)continue;
                if(scene!=null && NotificationCategory.of(type)!=scene.category())continue;
                for(var item:scene == null ? channels.subscriptionTemplates("WECHAT_MINI_PROGRAM",type)
                        : channels.find("WECHAT_MINI_PROGRAM") == null ? List.<NotificationTemplateStatus>of()
                        : channels.find("WECHAT_MINI_PROGRAM").subscriptionTemplates(type,scene == NotificationSubscriptionScene.MENTOR_QUESTION_PUBLISH)) if("READY".equals(item.status())) {
                    byId.computeIfAbsent(item.templateId(),ignored->new ArrayList<>()).add(type);
                    metadataById.put(item.templateId(),item);
                }
            }
            var items=new ArrayList<NotificationCatalogBO.Option>();
            byId.forEach((id,types)->{
                var metadata=metadataById.get(id);
                items.add(new NotificationCatalogBO.Option(id,metadata.title(),metadata.subscriptionKind(),
                        types.stream().map(t->NotificationCategory.of(t).name()).distinct().sorted().toList(),types.stream().map(Enum::name).distinct().sorted().toList()));
            });
            return new NotificationCatalogBO.Options(now(),channels.configurationRevision(),3,List.copyOf(items),scene==null?null:scene.name(),resourceId==null?null:resourceId.toString());
        }catch(org.springframework.dao.DataAccessException unavailable){throw unavailable();}
    }
    public NotificationCatalogBO.Status status(){
        var items=new ArrayList<NotificationCatalogBO.StatusItem>(99);
        for(var type:NotificationCategory.types())for(var channel:NotificationChannel.values()){
            var state=channel==NotificationChannel.IN_APP
                    ?new NotificationTemplateStatus("READY",List.of(),null,null,null)
                    :channels.status(channel.name(),type);
            items.add(new NotificationCatalogBO.StatusItem(NotificationCategory.of(type).name(),type.name(),channel.name(),state.status(),state.issues(),state.templateId(),state.title(),state.subscriptionKind()));
        }
        return new NotificationCatalogBO.Status(now(),channels.configurationRevision(),List.copyOf(items));
    }
    public static OffsetDateTime now(){return OffsetDateTime.now(ZoneId.of("Asia/Shanghai"));}
    public static ContractProblemException unavailable(){return new ContractProblemException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"/problems/service-unavailable","通知依赖暂不可用");}
}
