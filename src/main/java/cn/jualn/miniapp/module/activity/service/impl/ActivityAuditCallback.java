package cn.jualn.miniapp.module.activity.service.impl;

import cn.jualn.miniapp.common.annotation.AuditTarget;
import cn.jualn.miniapp.common.enums.ActivityStatus;
import cn.jualn.miniapp.common.enums.AuditScene;
import cn.jualn.miniapp.common.enums.NotifyType;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.infrastructure.queue.contract.QueueProducer;
import cn.jualn.miniapp.module.activity.entity.Activity;
import cn.jualn.miniapp.module.activity.mapper.ActivityMapper;
import cn.jualn.miniapp.module.audit.entity.ContentAuditLog;
import cn.jualn.miniapp.module.audit.enums.AuditStatus;
import cn.jualn.miniapp.module.audit.mapper.ContentAuditLogMapper;
import cn.jualn.miniapp.module.audit.service.AuditResultCallback;
import cn.jualn.miniapp.module.notify.entity.NotifyPlan;
import cn.jualn.miniapp.module.notify.mapper.NotifyPlanMapper;
import cn.jualn.miniapp.module.notify.payload.NotifyPayload;
import cn.jualn.miniapp.module.notify.service.NotifyService;
import cn.jualn.miniapp.module.wx.notice.data.AuditResultNoticeData;
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
    private final QueueProducer queueProducer;
    private final NotifyService notifyService;

    @Override
    public void onPass(Long activityId) {
        long total = contentAuditLogMapper.selectCount(
                new LambdaQueryWrapper<ContentAuditLog>()
                        .eq(ContentAuditLog::getTargetId, activityId)
                        .eq(ContentAuditLog::getTargetType, TargetType.ACTIVITY)
        );

        long passed = contentAuditLogMapper.selectCount(
                new LambdaQueryWrapper<ContentAuditLog>()
                        .eq(ContentAuditLog::getTargetId, activityId)
                        .eq(ContentAuditLog::getTargetType, TargetType.ACTIVITY)
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
        activityMapper.update(
                new LambdaUpdateWrapper<Activity>()
                        .set(Activity::getStatus, ActivityStatus.REJECTED.getCode())
                        .set(Activity::getAuditStatus, AuditStatus.REJECT.getCode())
                        .set(Activity::getRejectReason, reason)
                        .eq(Activity::getId, activityId)
        );

        sendAuditRejectNotification(activityId, reason);
    }

    private void sendAuditRejectNotification(Long activityId, String reason) {
        try {
            Activity activity = activityMapper.selectById(activityId);
            if (activity == null) return;

            NotifyPayload payload = NotifyPayload.builder()
                    .receiverId(activity.getUserId())
                    .senderId(null)
                    .type(NotifyType.AUDIT_RESULT)
                    .title("你的活动未通过审核")
                    .content(reason != null ? reason : "内容不符合社区规范")
                    .targetType(TargetType.ACTIVITY)
                    .targetId(activityId)
                    .wxData(new AuditResultNoticeData(
                            truncate(activity.getTitle(), 20),
                            "未通过",
                            reason != null ? reason : "内容不符合社区规范",
                            LocalDateTime.now()))
                    .build();
            queueProducer.send(payload);
        } catch (Exception e) {
            log.warn("[ActivityAudit] 审核通知发送失败，activityId={}", activityId, e);
        }
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}

