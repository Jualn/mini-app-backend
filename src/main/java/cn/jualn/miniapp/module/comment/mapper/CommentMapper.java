package cn.jualn.miniapp.module.comment.mapper;

import cn.jualn.miniapp.module.comment.entity.Comment;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Collection;
import java.util.List;

public interface CommentMapper extends BaseMapper<Comment> {
    @Select("SELECT id,target_type,target_id,parent_id,community_thread,status,deleted_at FROM comment WHERE id=#{id}")
    Comment selectInteractionTarget(@Param("id") long id);

    Comment lockReplyTarget(long id);

    Comment lockArticleComment(@Param("id") long id);

    Comment articleCommentIncludingDeleted(@Param("id") long id);

    List<Comment> articlePage(@Param("articleId") long articleId, @Param("parentId") Long parentId,
                              @Param("afterTime") java.time.LocalDateTime afterTime, @Param("afterId") Long afterId, @Param("size") int size);

    int deleteArticleComment(@Param("id") long id, @Param("userId") long userId, @Param("now") java.time.LocalDateTime now);

    long articleReplyCount(long id);

    List<cn.jualn.miniapp.module.comment.bo.CommentCountBO> visibleCounts(@Param("type") int type, @Param("ids") Collection<Long> ids);

    @Select("SELECT user_id FROM comment WHERE id = #{id} AND deleted_at IS NULL")
    Long selectUserIdById(Long id);

    List<Comment> selectTopNByParentIds(
            @Param("parentIds") Collection<Long> parentIds,
            @Param("limit") int limit
    );

    @Update("UPDATE comment SET reply_count = reply_count + 1 WHERE id = #{parentId} AND deleted_at IS NULL")
    void increaseReplyCount(Long parentId);

    @Update("UPDATE comment SET reply_count = reply_count - 1 WHERE id = #{parentId} AND deleted_at IS NULL AND reply_count > 0")
    void decreaseReplyCount(Long parentId);

    @Update("UPDATE comment SET like_count = #{count} WHERE id = #{commentId} AND deleted_at IS NULL")
    void setLikeCount(@Param("commentId") Long commentId, @Param("count") Long count);

    AdminCommentStateRow selectAdminStateById(@Param("commentId") Long commentId);

    int restoreAdminComment(@Param("commentId") Long commentId);

    int approveAdminReview(@Param("commentId") Long commentId);

    int rejectAdminReview(@Param("commentId") Long commentId);
}
