package cn.jualn.miniapp.module.publicevent.service;

import cn.jualn.miniapp.common.enums.*;
import cn.jualn.miniapp.module.notify.enums.NotifyType;
import cn.jualn.miniapp.module.notify.bo.ReminderPlanBO;
import cn.jualn.miniapp.module.notify.payload.NotifyPayload;
import cn.jualn.miniapp.module.notify.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
@RequiredArgsConstructor
public class PublicEventNotificationSource implements NotificationSource {
    private final PublicEventSubscriptionService subscriptions;
    public int sourceType() { return 2; }
    public TargetType targetType() { return TargetType.EXAM; }
    public long upperBound(long id,String scope) {
        if (!"SUBSCRIBERS".equals(scope)) throw new IllegalArgumentException("Unsupported PublicEvent scope");
        return subscriptions.subscriberUpperBound(id);
    }
    public List<Long> recipients(long id,String scope,long after,long upper,int limit) {
        if (scope!=null && !"SUBSCRIBERS".equals(scope)) throw new IllegalArgumentException("Unsupported PublicEvent scope");
        return subscriptions.listSubscriberUserIds(id,after,upper,limit);
    }
    public NotifyPayload reminder(long user,ReminderPlanBO plan) {
        return NotifyPayload.builder().receiverId(user).type(NotifyType.fromCode(plan.getNotifyType()))
            .title(plan.getTitle()).content(plan.getContent()).targetType(targetType()).targetId(plan.getSourceId())
            .sourceKey(ReminderOccurrence.sourceKey(plan,user)).reminderPlanId(plan.getId())
            .businessTime(plan.getSubjectStartsAt()).businessLocation(plan.getSubjectLocation()).build();
    }
    public Map<String,Object> content(NotifyPayload payload) {
        String key=switch(payload.getType()) {
            case PUBLIC_EVENT_START_REMINDER -> "publicEventStartTime";
            case PUBLIC_EVENT_REGISTRATION_DEADLINE_REMINDER -> "registrationDeadlineTime";
            default -> null;
        };
        if(key==null || payload.getBusinessTime()==null) return Map.of();
        Map<String,Object> result=new LinkedHashMap<>();result.put(key,payload.getBusinessTime().toString());
        result.put("businessTime",payload.getBusinessTime().toString());
        if(payload.getBusinessLocation()!=null) result.put("location",payload.getBusinessLocation());
        return result;
    }
}
