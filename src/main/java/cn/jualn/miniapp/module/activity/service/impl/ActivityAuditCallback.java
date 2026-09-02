package cn.jualn.miniapp.module.activity.service.impl;

import cn.jualn.miniapp.common.annotation.AuditTarget;
import cn.jualn.miniapp.common.enums.ActivityStatus;
import cn.jualn.miniapp.common.enums.AuditScene;
import cn.jualn.miniapp.common.enums.NotifyType;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.module.activity.entity.Activity;
import cn.jualn.miniapp.module.activity.mapper.ActivityMapper;
import cn.jualn.miniapp.module.audit.entity.ContentAuditLog;
import cn.jualn.miniapp.module.audit.enums.AuditStatus;
import cn.jualn.miniapp.module.audit.mapper.ContentAuditLogMapper;
import cn.jualn.miniapp.module.audit.service.AuditResultCallback;
import cn.jualn.miniapp.module.notify.entity.NotifyPlan;
import cn.jualn.miniapp.module.notify.mapper.NotifyPlanMapper;
import cn.jualn.miniapp.module.notify.service.NotifyService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
@AuditTarget(AuditScene.ACTIVITY)
public class ActivityAuditCallback implements AuditResultCallback {

    private final ActivityMapper activityMapper;
    private final ContentAuditLogMapper contentAuditLogMapper;
    private final NotifyPlanMapper notifyPlanMapper;
    private final NotifyService notifyService;

    @Override
    public void onPass(Long activityId) {
        long total = contentAuditLogMapper.selectCount(
                new LambdaQueryWrapper<ContentAuditLog>()
                        .eq(ContentAuditLog::getTargetId, activityId)
                        .eq(ContentAuditLog::getTargetType, TargetType.ACTIVITY.getCode())
        );

        long passed = contentAuditLogMapper.selectCount(
                new LambdaQueryWrapper<ContentAuditLog>()
                        .eq(ContentAuditLog::getTargetId, activityId)
                        .eq(ContentAuditLog::getTargetType, TargetType.ACTIVITY.getCode())
                        .eq(ContentAuditLog::getFinalResult, 1)
        );

        if (passed < total) {
            return;
        }

        activityMapper.update(
                new LambdaUpdateWrapper<Activity>()
                        .set(Activity::getStatus, ActivityStatus.SIGNUP.getCode())
                        .set(Activity::getAuditStatus, AuditStatus.PASS.getCode())
                        .set(Activity::getPublishedAt, LocalDateTime.now())
                        .eq(Activity::getId, activityId)
                        .eq(Activity::getAuditStatus, AuditStatus.PENDING.getCode())
        );

        // 创建活动开始提醒的延迟通知计划
        createActivityRemindPlan(activityId);
    }

    private void createActivityRemindPlan(Long activityId) {
        try {
            Activity activity = activityMapper.selectById(activityId);
            if (activity == null || activity.getStartTime() == null) return;

            // 活动开始前1小时发送提醒
            LocalDateTime remindAt = activity.getStartTime().minusHours(1);
            if (remindAt.isBefore(LocalDateTime.now())) return; // 已过期，不创建

            NotifyPlan plan = NotifyPlan.builder()
                    .sourceType(1) // 1=活动
                    .sourceId(activityId)
                    .notifyType(NotifyType.ACTIVITY_REMIND.getCode())
                    .title("活动即将开始")
                    .content("你报名的活动「" + activity.getTitle() + "」即将开始")
                    .scope(0) // 0=已报名用户
                    .scene("活动开始前1小时")
                    .sendAt(remindAt)
                    .status(0)
                    .build();
            notifyPlanMapper.insert(plan);

            // 入延迟队列
            notifyService.enqueueNotifyPlan(plan.getId());
            log.info("[ActivityAudit] 活动提醒计划已创建，activityId={}, planId={}, remindAt={}",
                    activityId, plan.getId(), remindAt);
        } catch (Exception e) {
            log.warn("[ActivityAudit] 创建活动提醒计划失败，activityId={}", activityId, e);
        }
    }

    @Override
    public void onReject(Long activityId, String reason) {
        int rows = activityMapper.update(
                new LambdaUpdateWrapper<Activity>()
                        .set(Activity::getStatus, ActivityStatus.PENDING.getCode())
                        .set(Activity::getAuditStatus, AuditStatus.PENDING.getCode())
                        .set(Activity::getRejectReason, null)
                        .eq(Activity::getId, activityId)
                        .ne(Activity::getStatus, ActivityStatus.DELETED.getCode())
        );

        if (rows > 0) {
            log.info("[ActivityAudit] 机器风险内容已转人工复核，activityId={}, reason={}", activityId, reason);
        }
    }
}

