package cn.jualn.miniapp.module.content.service;

import cn.jualn.miniapp.module.notify.service.NotificationContentAccess;
import cn.jualn.miniapp.module.notify.service.NotificationDeliveryEligibility;
import cn.jualn.miniapp.module.notify.bo.NotificationDeliveryBO;
import cn.jualn.miniapp.module.post.service.GrowthRecordAccess;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.Map;

@Service @RequiredArgsConstructor
public class GrowthNotificationAccess implements NotificationContentAccess,NotificationDeliveryEligibility {
    private final GrowthManagementTargets targets;
    private final GrowthRecordAccess records;
    public Decision evaluate(long recipient,Integer type,Long id){
        if(id==null || type==null || type!=1 && type!=4)return new Decision(false,true,null);
        Long post=targets.root(type==1?"post":"comment",id);
        return post==null?new Decision(false,true,null):new Decision(true,records.mayRead(post,recipient,true),post.toString());
    }
    public boolean isEligible(NotificationDeliveryBO n,Map<String,Object> frozen){return evaluate(n.userId(),n.targetType(),n.targetId()).allowed();}
}
