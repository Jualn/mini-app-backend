package cn.jualn.miniapp.module.notify.service;

import cn.jualn.miniapp.common.result.PageResult;
import cn.jualn.miniapp.module.notify.bo.NotificationBO;
import cn.jualn.miniapp.module.notify.bo.NotificationPageBO;
import cn.jualn.miniapp.module.notify.handler.ContentBroadcastHandler;
import cn.jualn.miniapp.module.notify.handler.NotifyHandler;
import cn.jualn.miniapp.module.notify.payload.NotifyPayload;

/**
 * 通知服务接口（按 NOTIFICATION_DESIGN.md 设计）。
 * <p>
 * 核心职责：
 * - 通知增删查改（站内收件箱维护）
 * - 基于延迟队列处理到期通知计划
 */
public interface NotifyService {

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
	 * 把通知计划投递到延迟队列。
	 * 供“创建/更新通知计划”等业务流程调用。
	 *
	 * @param planId 通知计划 ID
	 */
	void enqueueNotifyPlan(Long planId);

	/** 作废指定活动仍处于待发送状态的通知计划。 */
	void cancelActivityPlans(Long activityId);

	/** 向活动当前有效订阅者投递活动状态变更通知。 */
	void notifyActivitySubscribers(Long activityId, String title, String content);

	/**
	 * 执行单个通知计划的 fan-out（分页查询订阅用户，批量发送通知）。
	 * 由 {@link ContentBroadcastHandler} 调用。
	 *
	 * @param planId 通知计划 ID
	 */
	void broadcastPlanFanOut(Long planId);

	/**
	 * 处理来自队列的个人通知（检查开关、写 Notification、更新 Redis、推微信）。
	 * 由 {@link NotifyHandler} 调用。
	 *
	 * @param payload 个人通知负载
	 */
	void processNotificationPayload(NotifyPayload payload);
}
