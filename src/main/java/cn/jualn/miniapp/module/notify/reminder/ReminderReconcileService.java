package cn.jualn.miniapp.module.notify.reminder;

import cn.jualn.miniapp.common.observability.ObservabilityContext;
import cn.jualn.miniapp.infrastructure.async.job.JobDefinition;
import cn.jualn.miniapp.infrastructure.async.job.JobService;
import cn.jualn.miniapp.module.notify.async.NotificationPlanJobPayload;
import cn.jualn.miniapp.module.notify.entity.NotifyPlan;
import cn.jualn.miniapp.module.notify.mapper.NotifyPlanMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ReminderReconcileService {
    private final NotifyPlanMapper planMapper;
    private final JobService jobService;

    @Transactional(propagation = Propagation.MANDATORY)
    public void reconcile(int sourceType, long sourceId, long generation,
                          List<ReminderSpec> expected, LocalDateTime now) {
        LocalDateTime evaluatedAt = now == null ? LocalDateTime.now() : now;
        List<ReminderSpec> validRules = expected == null ? List.of() : expected.stream()
                .filter(spec -> spec != null && spec.sendAt() != null)
                .toList();
        List<NotifyPlan> current = planMapper.selectActiveBySource(sourceType, sourceId);
        Set<String> kept = new HashSet<>();

        for (NotifyPlan plan : current) {
            ReminderSpec match = validRules.stream()
                    .filter(spec -> sameIntent(plan, spec))
                    .findFirst().orElse(null);
            if (match != null) {
                kept.add(key(match));
            } else if (planMapper.cancelPlan(plan.getId()) == 1) {
                jobService.cancelPending("notification.plan.fanout", planDedupeKey(plan.getId()));
            }
        }

        for (ReminderSpec spec : validRules) {
            if (kept.contains(key(spec))) continue;
            if (!spec.sendAt().isAfter(evaluatedAt)) continue;
            if (!Set.of("SUBSCRIBERS", "SUBSCRIBERS_NOT_REGISTERED").contains(spec.recipientScope())) {
                throw new IllegalArgumentException("Unsupported reminder recipient scope");
            }
            if (sourceType != 1 && "SUBSCRIBERS_NOT_REGISTERED".equals(spec.recipientScope())) {
                throw new IllegalArgumentException("SUBSCRIBERS_NOT_REGISTERED is Activity-only");
            }
            NotifyPlan plan = NotifyPlan.builder()
                    .sourceType(sourceType).sourceId(sourceId)
                    .timelineId(spec.timelineId()).ruleKey(spec.ruleKey()).generation(generation)
                    .recipientScope(spec.recipientScope())
                    .notifyType(spec.notificationType().getCode())
                    .title(spec.title()).content(spec.content())
                    .subjectStartsAt(spec.subjectStartsAt()).subjectLocation(spec.subjectLocation())
                    .scope(0).scene(spec.ruleKey()).sendAt(spec.sendAt()).status(0)
                    .build();
            planMapper.insert(plan);
            jobService.create(new JobDefinition(
                    "notification.plan.fanout", 1, ObservabilityContext.ensureOperationId(),
                    planDedupeKey(plan.getId()), "notify-plan", String.valueOf(plan.getId()),
                    new NotificationPlanJobPayload(plan.getId()), plan.getSendAt(), 6));
        }
    }

    private boolean sameIntent(NotifyPlan plan, ReminderSpec spec) {
        return Long.valueOf(spec.timelineId()).equals(plan.getTimelineId())
                && spec.ruleKey().equals(plan.getRuleKey())
                && spec.sendAt().equals(plan.getSendAt())
                && spec.recipientScope().equals(plan.getRecipientScope())
                && Integer.valueOf(spec.notificationType().getCode()).equals(plan.getNotifyType())
                && java.util.Objects.equals(spec.subjectStartsAt(), plan.getSubjectStartsAt())
                && java.util.Objects.equals(spec.subjectLocation(), plan.getSubjectLocation());
    }

    private String key(ReminderSpec spec) {
        return spec.timelineId() + ":" + spec.ruleKey() + ":" + spec.sendAt();
    }

    private String planDedupeKey(Long planId) {
        return "plan:" + planId + ":fanout";
    }
}
