package cn.jualn.miniapp.module.activity.service;

import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.module.activity.bo.ActivityEnrollBO;
import cn.jualn.miniapp.module.activity.entity.ActivityEnrollment;

import java.util.List;
import java.util.Set;

/**
 * 活动报名业务接口。
 *
 * <p>该接口管理用户对活动的报名状态。支持的操作包括报名、取消报名、状态查询和通知设置。
 * 用户只能管理自己的报名记录，权限检查由实现类通过UserContext获取当前用户ID后进行。</p>
 *
 * <p><b>报名状态说明</b>：</p>
 * <ul>
 *   <li>1 - 已报名（正常状态）</li>
 *   <li>2 - 已取消报名（可重新报名，记录不删除）</li>
 * </ul>
 *
 * <p><b>缓存机制</b>：报名状态会被缓存到Redis，支持快速的报名检查。已取消的报名记录可以
 * 通过再次报名来恢复，实现了"软取消"模式。</p>
 *
 * @author miniapp
 * @since 2026-04-28
 */
public interface ActivityEnrollmentService {
    /** Read the caller's persisted relationship even when its subject is no longer public. */
    cn.jualn.miniapp.module.activity.bo.ActivitySubscriptionBO getSubscriptionState(Long activityId);

    cn.jualn.miniapp.module.activity.bo.ActivitySubscriptionBO subscribeWithState(Long activityId);


    /**
     * 报名活动。
     *
     * <p>该方法将当前用户报名到指定活动。如果用户已取消报名过该活动，则将状态改为已报名。
     * 如果用户已经报名，则保持现状不做操作。</p>
     *
     * <p><b>参数说明</b>：报名类型（type）用于区分正式报名还是订阅，可扩展支持不同的报名流程。</p>
     *
     * @param activityId 报名业务对象，包含活动ID、报名类型等信息
     *
     * @throws BusinessException 当用户未登录时抛出 UNAUTHORIZED
     * @throws BusinessException 当活动不存在时抛出 NOT_FOUND
     */
    void enrollActivity(Long activityId);

    /**
     * 取消报名活动。
     *
     * <p>该方法将用户的报名状态设置为已取消。取消的报名记录不会从数据库删除，
     * 用户可以再次报名来恢复。</p>
     *
     * <p><b>软删除说明</b>：不会真正删除数据，只是修改状态字段。这样可以保留用户的
     * 操作历史，并且能够实现"恢复报名"功能。</p>
     *
     * @param activityId 包含要取消报名的活动ID
     *
     * @throws BusinessException 当用户未登录时抛出 UNAUTHORIZED
     * @throws BusinessException 当活动不存在时抛出 NOT_FOUND
     */
    void unEnrollActivity(Long activityId);

    /**
     * 检查当前用户是否已报名活动。
     *
     * <p>该方法执行快速检查，返回布尔值。结果会被缓存到Redis以支持高频查询。
     * 该方法通常被其他模块调用来判断用户的报名状态。</p>
     *
     * @param activityId 活动ID
     * @return 当前用户已报名该活动则返回true，否则返回false；未登录用户返回false
     */
    boolean isEnrolled(Long activityId);

    /**
     * 获取当前用户对活动的报名记录。
     *
     * <p>该方法返回完整的报名记录对象，包含所有字段信息。如果用户未报名，返回null。</p>
     *
     * @param activityId 活动ID
     * @return 报名记录对象，如果未报名则返回null
     *
     * @throws cn.jualn.miniapp.common.exception.BusinessException 当用户未登录时抛出 UNAUTHORIZED
     */
    ActivityEnrollment getEnrollment(Long activityId);

    /**
     * 更新报名的通知开启状态。
     *
     * <p>该方法允许用户在报名后修改是否接收相关通知。通知标志只对已报名的记录有效。</p>
     *
     * @param command 包含活动ID和通知标志的业务对象
     *
     * @throws cn.jualn.miniapp.common.exception.BusinessException 当用户未登录时抛出 UNAUTHORIZED
     * @throws cn.jualn.miniapp.common.exception.BusinessException 当报名记录不存在时抛出 NOT_FOUND
     */
    void updateNotifyEnable(ActivityEnrollBO command);

    /**
     * 分页获取指定活动的已报名用户 ID 列表（用于广播）。
     *
     * @param activityId 活动 ID
     * @param lastId 游标 lastId（大于该 id）
     * @param limit 分页大小
     * @return 用户 ID 列表（按 id 升序）
     */
    List<Long> listEnrolledUserIds(Long activityId, long lastId, int limit);

    /** Current notify-enabled subscribers minus current submitted platform registrations. */
    List<Long> listNotifyEnabledUnregisteredUserIds(Long activityId, long lastId, int limit);

    List<Long> listSubscriberOrRegisteredUserIds(Long activityId, long lastId, long upperUserId, int limit);

    long subscriberOrRegisteredUpperBound(Long activityId);

    /**
     * 批量获取当前用户在给定活动列表中的已报名活动 ID。
     *
     * @param activityIds 活动 ID 列表
     * @return 已报名活动 ID 集合
     */
    Set<Long> listEnrolledActivityIds(List<Long> activityIds);

    long countActiveSubscribers(Long activityId);

    long countNotifyEnabledSubscribers(Long activityId);

}
