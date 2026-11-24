package cn.jualn.miniapp.module.activity.service;

import cn.jualn.miniapp.module.activity.entity.Activity;
import cn.jualn.miniapp.module.notify.enums.NotifyType;
import cn.jualn.miniapp.module.notify.model.NotificationSubscriptionScene;
import cn.jualn.miniapp.module.notify.service.NotificationSubscriptionContext;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service @RequiredArgsConstructor
public class ActivitySubscriptionOptions implements NotificationSubscriptionContext {
    private final ActivityQueries queries;
    private final ActivityEnrollmentService subscriptions;
    private final ActivityRegistrationService registrations;
    public boolean supports(NotificationSubscriptionScene scene) {
        return scene == NotificationSubscriptionScene.ACTIVITY_FOLLOW || scene == NotificationSubscriptionScene.ACTIVITY_REGISTER;
    }
    public List<NotifyType> types(NotificationSubscriptionScene scene, long id) {
        var detail = queries.getActivityResource(id); // Existing visibility and participation authority.
        var now = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
        if (!Integer.valueOf(1).equals(detail.getPublishStatus()) || !Integer.valueOf(0).equals(detail.getLifecycleStatus())
                || "ENDED".equals(detail.getStageState()) || "CANCELLED".equals(detail.getStageState())) return List.of();
        var mine = registrations.getMine(id);
        boolean registered = mine != null && Integer.valueOf(1).equals(mine.getStatus());
        boolean subscribed = subscriptions.getSubscriptionState(id).subscribed();
        var subject = new Activity();
        subject.setPublishStatus(detail.getPublishStatus()); subject.setLifecycleStatus(detail.getLifecycleStatus());
        subject.setRegistrationMode(detail.getRegistrationMode()); subject.setTitle(detail.getTitle());
        var rules = new ActivityReminderPolicy().evaluate(subject,detail.getTimelineItems(),now).stream()
                .filter(rule -> rule.subjectStartsAt() != null && rule.subjectStartsAt().isAfter(now)).toList();
        var result = new ArrayList<NotifyType>();
        if (scene == NotificationSubscriptionScene.ACTIVITY_REGISTER) {
            if (registered || !"OPEN".equals(detail.getParticipationState())) return List.of();
            result.add(NotifyType.ACTIVITY_CANCELLED); result.add(NotifyType.ACTIVITY_TIME_CHANGED);
            result.add(NotifyType.ACTIVITY_LOCATION_CHANGED);
            if (rules.stream().anyMatch(rule -> rule.notificationType() == NotifyType.ACTIVITY_START_REMINDER)) result.add(NotifyType.ACTIVITY_START_REMINDER);
        } else {
            if (registered || subscribed) return List.of();
            boolean deadline = rules.stream().anyMatch(rule -> rule.notificationType() == NotifyType.ACTIVITY_REGISTRATION_DEADLINE_REMINDER);
            if (deadline && rules.stream().anyMatch(rule -> rule.notificationType() == NotifyType.ACTIVITY_REGISTRATION_START_REMINDER))
                result.add(NotifyType.ACTIVITY_REGISTRATION_START_REMINDER);
            if (deadline) result.add(NotifyType.ACTIVITY_REGISTRATION_DEADLINE_REMINDER);
        }
        return List.copyOf(result);
    }
}
