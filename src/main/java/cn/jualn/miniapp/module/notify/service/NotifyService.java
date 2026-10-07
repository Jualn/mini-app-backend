package cn.jualn.miniapp.module.notify.service;

import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.enums.NotifyType;

import cn.jualn.miniapp.common.result.PageResult;
import cn.jualn.miniapp.module.notify.bo.NotificationBO;
import cn.jualn.miniapp.module.notify.bo.NotificationPageBO;
import cn.jualn.miniapp.module.notify.payload.NotifyPayload;
import cn.jualn.miniapp.module.notify.reminder.ReminderSpec;
import cn.jualn.miniapp.infrastructure.async.job.JobExecutionContext;
import cn.jualn.miniapp.module.notify.async.BusinessNotificationJobPayload;

/**
 * 通知服务接口；业务与投递边界见 docs/reminder-notification.md。
 * <p>
 * 核心职责：
 * - 通知增删查改（站内收件箱维护）
 * - 基于持久化 Job 处理到期通知计划
 */
public interface NotifyService {
    void enqueueBusinessNotification(TargetType targetType, Long targetId, String eventIdentity,
            NotifyType notificationType, String recipientScope, String title, String content,
            cn.jualn.miniapp.module.notify.bo.NotificationCenterBO.Snapshot snapshot);
    /** Legacy compatibility entry: cancel active plans only; canonical owners use reconcileReminders. */
    void replaceEventReminder(cn.jualn.miniapp.common.enums.TargetType type, Long id, String title,
                              java.time.LocalDateTime sendAt);

    void reconcileReminders(cn.jualn.miniapp.common.enums.TargetType type, Long id, long generation,
                            java.util.List<ReminderSpec> expected, java.time.LocalDateTime now);


	/**
	 * 分页查询当前用户的通知列表。
	 */
	PageResult<NotificationBO> pageCurrentUserNotifications(NotificationPageBO query);

	/**
	 * 查询当前用户的未读通知数（从 Redis 缓存读，不走 DB）。
	 */
	Long countCurrentUnreadNotifications();

	/**
	 * 标记单条通知为已读。
	 */
	void markAsRead(Long notificationId);

	/**
	 * 标记所有通知为已读。
	 */
	void markAllAsRead();

	/**
	 * 为通知计划创建持久化 Job。
	 * 供“创建/更新通知计划”等业务流程调用。
	 *
	 * @param planId 通知计划 ID
	 */
	void enqueueNotifyPlan(Long planId);

	/** 作废指定活动仍处于待发送状态的通知计划。 */
	void cancelActivityPlans(Long activityId);

	/** 在当前业务事务中持久化活动事件 fan-out intent，不创建 ReminderPlan。 */
	void enqueueActivityBusinessNotification(Long activityId, String eventKey, String title, String content);

	/** Job 执行时向活动当前有效订阅者 fan-out；稳定 eventKey 吸收重放。 */
	void notifyActivitySubscribers(Long activityId, String eventKey, String title, String content);

	/**
	 * 执行单个通知计划的 fan-out（分页查询订阅用户，批量发送通知）。
	 * 由通知计划 Job Handler 调用。
	 *
	 * @param planId 通知计划 ID
	 */
	void broadcastPlanFanOut(Long planId);

    void broadcastPlanFanOut(Long planId, JobExecutionContext context);

    void enqueueBusinessNotification(cn.jualn.miniapp.common.enums.TargetType targetType, Long targetId,
                                     String eventIdentity, cn.jualn.miniapp.common.enums.NotifyType notificationType,
                                     String recipientScope, String title, String content);

    void broadcastBusinessNotification(BusinessNotificationJobPayload payload, JobExecutionContext context);

	/**
	 * 处理个人通知意图（检查开关、写 Notification、更新 Redis、创建微信投递 Job）。
	 * 由业务服务或异步 Handler 调用。
	 *
	 * @param payload 个人通知负载
	 */
	void processNotificationPayload(NotifyPayload payload);
}
