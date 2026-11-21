package cn.jualn.miniapp.module.publicevent.service;

import cn.jualn.miniapp.module.notify.enums.NotifyType;
import cn.jualn.miniapp.module.notify.model.NotificationSubscriptionScene;
import cn.jualn.miniapp.module.notify.service.NotificationSubscriptionContext;
import cn.jualn.miniapp.module.publicevent.entity.PublicEvent;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service @RequiredArgsConstructor
public class PublicEventSubscriptionOptions implements NotificationSubscriptionContext {
    private final PublicEventQueries queries;
    public boolean supports(NotificationSubscriptionScene scene) { return scene == NotificationSubscriptionScene.PUBLIC_EVENT_FOLLOW; }
    public List<NotifyType> types(NotificationSubscriptionScene scene, long id) {
        var detail = queries.getPublicEventResource(id);
        if (Boolean.TRUE.equals(detail.getSubscribed())) return List.of();
        var subject = new PublicEvent(); subject.setPublishStatus(detail.getPublishStatus());
        subject.setLifecycleStatus(detail.getLifecycleStatus()); subject.setTitle(detail.getTitle());
        var now = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
        return new PublicEventReminderPolicy().evaluate(subject,detail.getTimelineItems(),now).stream()
                .filter(rule -> rule.notificationType() == NotifyType.PUBLIC_EVENT_REGISTRATION_DEADLINE_REMINDER
                        && rule.subjectStartsAt() != null && rule.subjectStartsAt().isAfter(now))
                .map(rule -> rule.notificationType()).distinct().toList();
    }
}
