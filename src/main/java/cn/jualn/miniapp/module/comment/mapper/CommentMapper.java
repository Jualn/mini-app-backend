package cn.jualn.miniapp.module.comment.mapper;

import cn.jualn.miniapp.module.comment.entity.Comment;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Collection;
import java.util.List;

public interface CommentMapper extends BaseMapper<Comment> {

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
