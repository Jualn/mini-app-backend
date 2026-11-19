package cn.jualn.miniapp.module.activity.service;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.observability.ObservabilityContext;
import cn.jualn.miniapp.infrastructure.async.job.*;
import cn.jualn.miniapp.module.activity.entity.Activity;
import cn.jualn.miniapp.module.timeline.service.TimelineService;
import cn.jualn.miniapp.module.notify.async.ReminderCatchUpPayload;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.LocalDateTime;
import java.time.ZoneId;

@Service
@RequiredArgsConstructor
public class ActivityReminderCatchUp {
    private final JobService jobs;
    private final TimelineService timeline;
    @Transactional(propagation = Propagation.MANDATORY)
    public void joined(Activity activity, long user, boolean registration) {
        var now = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
        var rules = new ActivityReminderPolicy().evaluate(activity, timeline.listTimelinesByTarget(TargetType.ACTIVITY, activity.getId()), now);
        for (var rule : rules) {
            if (registration && !"ACTIVITY_START_REMINDER".equals(rule.ruleKey())) continue;
            if (rule.sendAt().isAfter(now) || rule.subjectStartsAt() == null || !now.isBefore(rule.subjectStartsAt())) continue;
            // A new accepted relationship intent owns this execution; Notification identity remains anchor+recipient.
            jobs.create(new JobDefinition("notification.reminder.catch-up", 1, ObservabilityContext.ensureOperationId(),
                    "activity:" + activity.getId() + ":join:" + java.util.UUID.randomUUID(), "activity", activity.getId().toString(),
                    new ReminderCatchUpPayload(activity.getId(), user, rule), now, 6));
        }
    }
}
