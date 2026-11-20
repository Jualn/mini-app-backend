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
import lombok.RequiredArgsConstructor;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.time.OffsetDateTime;
import java.time.ZoneId;
@Service @RequiredArgsConstructor
public class NotificationCatalogQueries {
    private final NotificationChannels channels;
    public NotificationCatalogBO.Options options(NotificationCategory category){
        if(UserContext.getUserId()==null)throw new BusinessException(ResultCode.UNAUTHORIZED);
        try {
            var byId=new TreeMap<String,java.util.List<cn.jualn.miniapp.module.notify.enums.NotifyType>>();
            var metadataById=new TreeMap<String,cn.jualn.miniapp.module.notify.channel.NotificationTemplateStatus>();
            for(var type:NotificationCategory.types()){
                if(category!=null && NotificationCategory.of(type)!=category)continue;
                for(var item:channels.subscriptionTemplates("WECHAT_MINI_PROGRAM",type)) if("READY".equals(item.status())) {
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
            return new NotificationCatalogBO.Options(now(),channels.configurationRevision(),1,List.copyOf(items));
        }catch(org.springframework.dao.DataAccessException unavailable){throw unavailable();}
    }
    public NotificationCatalogBO.Status status(){
        var items=new ArrayList<NotificationCatalogBO.StatusItem>(99);
        for(var type:NotificationCategory.types())for(var channel:NotificationChannel.values()){
            var state=channel==NotificationChannel.IN_APP
                    ?new cn.jualn.miniapp.module.notify.channel.NotificationTemplateStatus("READY",List.of(),null,null,null)
                    :channels.status(channel.name(),type);
            items.add(new NotificationCatalogBO.StatusItem(NotificationCategory.of(type).name(),type.name(),channel.name(),state.status(),state.issues(),state.templateId(),state.title(),state.subscriptionKind()));
        }
        return new NotificationCatalogBO.Status(now(),channels.configurationRevision(),List.copyOf(items));
    }
    public static OffsetDateTime now(){return OffsetDateTime.now(ZoneId.of("Asia/Shanghai"));}
    public static ContractProblemException unavailable(){return new ContractProblemException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"/problems/service-unavailable","通知依赖暂不可用");}
}
