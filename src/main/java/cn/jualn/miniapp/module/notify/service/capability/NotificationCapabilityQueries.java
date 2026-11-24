package cn.jualn.miniapp.module.notify.service.capability;
import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.module.notify.bo.NotificationCapabilitiesBO;
import cn.jualn.miniapp.module.notify.channel.NotificationChannels;
import cn.jualn.miniapp.module.notify.model.NotificationCategory;
import cn.jualn.miniapp.module.notify.model.NotificationChannel;
import cn.jualn.miniapp.module.user.service.UserNotificationIdentityService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
@Service @RequiredArgsConstructor
public class NotificationCapabilityQueries {
    private final UserNotificationIdentityService userIdentities;
    private final NotificationChannels channels;
    public NotificationCapabilitiesBO getCapabilities(){
        Long user=UserContext.getUserId();if(user==null)throw new BusinessException(ResultCode.UNAUTHORIZED);
        try {
            var identity=userIdentities.identity(user);var items=new ArrayList<NotificationCapabilitiesBO.Item>(99);
            for(var type:NotificationCategory.types())for(var channel:NotificationChannel.values()){
                boolean inApp=channel==NotificationChannel.IN_APP;
                boolean mini=channel==NotificationChannel.WECHAT_MINI_PROGRAM;
                boolean ready=inApp || mini&&channels.available(channel.name(),type);
                var reasons=new ArrayList<String>();
                if(channel==NotificationChannel.WECHAT_OFFICIAL_ACCOUNT)reasons.add("CHANNEL_DISABLED");
                else if(!ready)reasons.add("NOT_CONFIGURED");
                if(mini&&!identity.miniProgram())reasons.add("IDENTITY_REQUIRED");
                items.add(new NotificationCapabilitiesBO.Item(NotificationCategory.of(type).name(),type.name(),channel.name(),ready,
                        inApp?"NOT_REQUIRED":ready?"PROVIDER_VERIFIED_AT_SEND":"UNKNOWN",List.copyOf(reasons)));
            }
            return new NotificationCapabilitiesBO(OffsetDateTime.now(ZoneId.of("Asia/Shanghai")),List.copyOf(items));
        }catch(org.springframework.dao.DataAccessException unavailable){throw new ContractProblemException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"/problems/service-unavailable","通知依赖暂不可用");}
    }
}
