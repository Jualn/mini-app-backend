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
        LocalDateTime evaluatedAt = now == null ? LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")) : now;
        List<ReminderSpec> validRules = expected == null ? List.of() : expected.stream()
                .filter(spec -> spec != null && spec.sendAt() != null)
                .toList();
        List<NotifyPlan> current = planMapper.selectActiveBySource(sourceType, sourceId);
        Set<String> kept = new HashSet<>();

        for (NotifyPlan plan : current) {
            ReminderSpec match = validRules.stream()
                    .filter(spec -> sameIntent(plan, spec, evaluatedAt))
                    .findFirst().orElse(null);
            if (match != null) {
                kept.add(key(match));
                planMapper.refreshPresentation(plan.getId(), match.title(), match.content(), match.subjectLocation(), match.timelineId());
            } else if (planMapper.cancelPlan(plan.getId()) == 1) {
                jobService.cancelPending("notification.plan.fanout", planDedupeKey(plan.getId()));
            }
        }

        for (ReminderSpec spec : validRules) {
            if (kept.contains(key(spec))) continue;
            boolean catchUp = sourceType == 1 && !spec.sendAt().isAfter(evaluatedAt) && spec.subjectStartsAt() != null && spec.subjectStartsAt().isAfter(evaluatedAt);
            if (!spec.sendAt().isAfter(evaluatedAt) && !catchUp) continue;
            if (!catchUp && spec.subjectStartsAt() != null && planMapper.hasCompletedOccurrence(sourceType, sourceId,
                    spec.ruleKey(), spec.subjectStartsAt())) continue;
            if (!Set.of("SUBSCRIBERS", "SUBSCRIBERS_NOT_REGISTERED", "SUBSCRIBERS_OR_REGISTERED_USERS").contains(spec.recipientScope())) {
                throw new IllegalArgumentException("Unsupported reminder recipient scope");
            }
            if (sourceType != 1 && !"SUBSCRIBERS".equals(spec.recipientScope())) {
                throw new IllegalArgumentException("SUBSCRIBERS_NOT_REGISTERED is Activity-only");
            }
            NotifyPlan plan = NotifyPlan.builder()
                    .sourceType(sourceType).sourceId(sourceId)
                    .timelineId(spec.timelineId()).ruleKey(spec.ruleKey()).generation(Math.max(generation,
                            Math.addExact(planMapper.maxGeneration(sourceType, sourceId, spec.timelineId(), spec.ruleKey()), 1L)))
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

    private boolean sameIntent(NotifyPlan plan, ReminderSpec spec, LocalDateTime now) {
        return (Integer.valueOf(1).equals(plan.getSourceType()) || Long.valueOf(spec.timelineId()).equals(plan.getTimelineId()))
                && spec.ruleKey().equals(plan.getRuleKey())
                && spec.sendAt().equals(plan.getSendAt())
                && spec.recipientScope().equals(plan.getRecipientScope())
                && Integer.valueOf(spec.notificationType().getCode()).equals(plan.getNotifyType())
                && java.util.Objects.equals(spec.subjectStartsAt(), plan.getSubjectStartsAt());
    }

    private String key(ReminderSpec spec) {
        return spec.timelineId() + ":" + spec.ruleKey() + ":" + spec.sendAt();
    }

    private String planDedupeKey(Long planId) {
        return "plan:" + planId + ":fanout";
    }
}
