package cn.jualn.miniapp.module.activity.service;

import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.PageResult;
import cn.jualn.miniapp.module.activity.bo.*;

/**
 * 活动业务接口。
 *
 * <p>该接口定义活动的核心业务操作，包括创建、修改、删除和查询。所有操作都以业务对象（BO）
 * 作为输入输出，与具体的HTTP请求/响应格式解耦。</p>
 *
 * <p><b>权限说明</b>：创建操作自动绑定当前用户，修改和删除操作仅限活动创建者执行。
 * 所有权限检查由实现类通过UserContext获取当前用户ID后进行验证。</p>
 *
 * <p><b>异常处理</b>：所有操作在遇到业务逻辑错误时都抛出 {@link BusinessException}，
 * 由Controller层统一处理并转换为HTTP响应。</p>
 *
 * @author miniapp
 * @since 2026-04-28
 */
public interface ActivityService {
    /** Authoritative public visibility without loading content or personal state. */
    boolean isPubliclyVisible(Long id);


    /**
     * 创建活动。
     *
     * <p>该方法创建新活动并关联提供的媒体附件。活动创建者自动设置为当前登录用户。</p>
     *
     * <p><b>参数说明</b>：BO对象中的字段都已由Controller层通过@Valid注解进行过合法性验证，
     * 该方法可以直接使用而无需二次验证。</p>
     *
     * @param command 创建业务对象，包含活动的所有基础信息和媒体列表
     * @return 新创建活动的数据库自增ID
     *
     * @throws BusinessException 当用户未登录时抛出 UNAUTHORIZED
     * @throws BusinessException 当媒体服务操作失败时抛出相应异常
     */
    Long createActivity(ActivityCreateBO command);

    /**
     * 更新活动（仅活动创建者或管理员可执行）。
     *
     * <p>该方法支持部分更新，BO对象中的null字段将被忽略。审核状态会被自动设置为待审核。</p>
     *
     * <p><b>权限控制</b>：只有活动的创建者可以编辑自己的活动。尝试编辑他人活动将抛出异常。</p>
     *
     * @param command 更新业务对象，包含活动ID和要更新的字段
     *
     * @throws BusinessException 当用户未登录时抛出 UNAUTHORIZED
     * @throws BusinessException 当活动不存在或用户无权编辑时抛出 NOT_FOUND
     * @throws BusinessException 当媒体服务操作失败时抛出相应异常
     */
    void updateActivity(ActivityUpdateBO command);

    /**
     * 删除活动（仅活动创建者或管理员可执行）。
     *
     * <p>该方法执行软删除（逻辑删除），不真正从数据库中移除记录。已删除的活动不会出现在
     * 查询结果中，但其ID仍会被保留以维护数据完整性。</p>
     *
     * <p><b>权限控制</b>：只有活动的创建者可以删除自己的活动。</p>
     *
     * @param id 包含要删除活动的ID
     *
     * @throws BusinessException 当用户未登录时抛出 UNAUTHORIZED
     * @throws BusinessException 当活动不存在或用户无权删除时抛出 NOT_FOUND
     */
    void removeActivity(Long id);

    /**
     * 获取活动详情。
     *
     * <p>该方法返回活动的完整详情，包括作者信息、媒体附件和当前用户的交互状态（是否点赞、是否报名）。
     * 数据会被缓存3分钟以提升性能，用户相关状态在每次查询时都会实时计算以保证准确性。</p>
     *
     * @param id 包含要查询活动的ID
     * @return 活动详情业务对象，包含完整信息
     *
     * @throws BusinessException 当活动不存在或已删除时抛出 NOT_FOUND
     */
    ActivityDetailBO getActivityDetail(Long id);

    /** Public contract read with current participation facts. */
    ActivityDetailBO getActivityResource(Long id);

    ActivityResourcePageBO pageActivityResources(ActivityPageBO query);

    /**
     * 活动分页列表。
     *
     * <p>该方法支持多条件过滤、关键词搜索和游标分页。返回的结果会被缓存30秒，
     * 适合列表展示和流式加载场景。</p>
     *
     * <p><b>分页说明</b>：使用游标分页（lastId），避免offset分页中的跳过问题。
     * 返回结果中的hasMore标志指示是否存在下一页。</p>
     *
     * @param command 查询业务对象，包含分页参数、过滤条件和排序方式
     * @return 分页结果，包含活动列表、是否有下一页和下次查询的游标
     */
    PageResult<ActivityListBO> pageActivityList(ActivityPageBO command);

    PageResult<ActivityListBO> searchActivities(String keyword, Long lastId, Integer pageSize);

    /** 管理端活动列表；实时读取数据库，不复用主应用列表缓存。 */
    AdminActivityPageBO pageAdminActivities(AdminActivityQueryBO query);

    /** 管理端活动聚合详情。 */
    AdminActivityDetailBO getAdminActivityDetail(Long id);

    /** 获取可编辑的管理端活动草稿。 */
    AdminActivityDetailBO getAdminActivityDraft(Long id);

    /** 管理员创建完整活动草稿，操作人由管理端身份显式传入。 */
    Long createAdminActivity(AdminActivitySaveBO command);

    /** 运维保存草稿、下架或已发布内容；已发布编辑保持发布。 */
    void updateAdminActivity(AdminActivitySaveBO command);

    AdminActivityDetailBO replaceAdminActivity(AdminActivitySaveBO command, String ifMatch);

    /** 直接发布/下架；旧审核方法仅保留拒绝响应以兼容旧调用方。 */
    void publishAdminActivity(Long activityId, Long operatorId);

    void takeDownAdminActivity(Long activityId, Long operatorId, String reason);

    /** Canonical conditional lifecycle transition; validates If-Match while holding the activity lock. */
    AdminActivityDetailBO transitionAdminActivity(Long activityId, Long operatorId, String ifMatch, String action);

    void submitAdminActivityReview(Long activityId, Long operatorId);

    /** 取消尚未开始的活动，并作废后续通知计划。 */
    void cancelAdminActivity(Long activityId, Long operatorId, String reason);

    /** 提前结束进行中的活动，并作废后续通知计划。 */
    void endAdminActivityEarly(Long activityId, Long operatorId, String reason);

    /** 管理员软删除活动。 */
    void removeAdminActivity(Long id, Long operatorId, String reason);

    /** 人工复核通过并发布机器风险活动。 */
    void approveActivityReview(Long activityId, Long operatorId, String remark);

    /** 人工复核拒绝机器风险活动。 */
    void rejectActivityReview(Long activityId, Long operatorId, String reason);

    /**
     * 增加活动评论数。
     *
     * <p>原子操作，由评论模块调用。</p>
     *
     * @param activityId 活动ID
     */
    void increaseCommentCount(Long activityId);

    /**
     * 减少活动评论数（最低为0）。
     *
     * <p>原子操作，确保并发安全且计数不会变为负数。</p>
     *
     * @param activityId 活动ID
     */
    void decreaseCommentCount(Long activityId);
}
