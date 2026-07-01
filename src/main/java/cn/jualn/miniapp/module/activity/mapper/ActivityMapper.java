package cn.jualn.miniapp.module.activity.mapper;

import cn.jualn.miniapp.module.activity.entity.Activity;
import cn.jualn.miniapp.module.interact.dto.inner.InteractCountDTO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.Collection;
import java.util.List;

/**
 * 活动数据访问层。
 *
 * <p>该类提供活动相关的数据库操作接口，包括基础CRUD和自定义业务查询。</p>
 *
 * @author miniapp
 * @since 2026-04-28
 */
@Mapper
public interface ActivityMapper extends BaseMapper<Activity> {

    /**
     * 根据ID查询活动（不包括已删除的活动）。
     *
     * <p>避免在业务逻辑中重复检查deleted_at字段的null状态，提高查询效率。</p>
     *
     * @param activityId 活动ID
     * @return 活动对象，如果活动不存在或已删除则返回null
     */
    Activity selectByIdNotDeleted(@Param("activityId") Long activityId);

    /**
     * 根据ID和用户ID查询活动（用于权限验证）。
     *
     * <p>一次查询同时获取活动和验证所有权，避免分开调用导致的多次查询和权限检查不一致。</p>
     *
     * @param activityId 活动ID
     * @param userId 用户ID
     * @return 活动对象，如果不存在或所有权不匹配则返回null
     */
    Activity selectByIdAndUserId(@Param("activityId") Long activityId, @Param("userId") Long userId);

    /**
     * 分页查询活动列表。
     *
     * <p>该查询支持状态、分类、关键词、游标分页。复杂动态SQL在XML中实现。</p>
     *
     * @param status 活动状态
     * @param category 分类，可为空
     * @param keyword 关键词，可为空
     * @param lastId 游标ID，可为空
     * @param limit 查询条数（通常为 pageSize + 1）
     * @return 活动列表
     */
    List<Activity> selectPageActivities(@Param("status") Integer status,
                                        @Param("category") Integer category,
                                        @Param("keyword") String keyword,
                                        @Param("lastId") Long lastId,
                                        @Param("limit") Integer limit);

    /**
     * 增加活动点赞数。
     *
     * <p>原子操作，使用SQL直接操作，避免读后写问题。</p>
     *
     * @param activityId 活动ID
     */
    @Update("UPDATE activity SET like_count = IFNULL(like_count, 0) + 1 WHERE id = #{activityId}")
    void increaseLikeCount(@Param("activityId") Long activityId);

    /**
     * 减少活动点赞数（最低为0）。
     *
     * <p>使用CASE语句保证计数不会变为负数。</p>
     *
     * @param activityId 活动ID
     */
    @Update("UPDATE activity SET like_count = IF(IFNULL(like_count, 0) > 0, like_count - 1, 0) WHERE id = #{activityId}")
    void decreaseLikeCount(@Param("activityId") Long activityId);

    /**
     * 增加活动评论数。
     *
     * <p>原子操作，使用SQL直接操作，避免读后写问题。</p>
     *
     * @param activityId 活动ID
     */
    @Update("UPDATE activity SET comment_count = IFNULL(comment_count, 0) + 1 WHERE id = #{activityId}")
    void increaseCommentCount(@Param("activityId") Long activityId);

    /**
     * 减少活动评论数（最低为0）。
     *
     * <p>使用CASE语句保证计数不会变为负数。</p>
     *
     * @param activityId 活动ID
     */
    @Update("UPDATE activity SET comment_count = IF(IFNULL(comment_count, 0) > 0, comment_count - 1, 0) WHERE id = #{activityId}")
    void decreaseCommentCount(@Param("activityId") Long activityId);

    @Update("UPDATE activity SET like_count = #{count} WHERE id = #{activityId} AND deleted_at IS NULL")
    void setLikeCount(@Param("activityId") Long activityId, @Param("count") Long count);

    Long selectViewCountById(@Param("id") Long id);

    List<InteractCountDTO> selectViewCountBatch(@Param("ids") Collection<Long> ids);

    int incrementViewCount(@Param("id") Long id, @Param("delta") Long delta);
}
