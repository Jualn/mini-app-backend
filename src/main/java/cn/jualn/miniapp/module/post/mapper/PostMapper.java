package cn.jualn.miniapp.module.post.mapper;

import cn.jualn.miniapp.module.interact.dto.inner.InteractCountDTO;
import cn.jualn.miniapp.module.post.bo.PostListBO;
import cn.jualn.miniapp.module.post.entity.Post;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Collection;
import java.util.List;

public interface PostMapper extends BaseMapper<Post> {
    @Select("SELECT * FROM post WHERE id=#{id}")
    Post includingDeleted(long id);
    @Select("<script>SELECT c.target_id AS post_id,COUNT(*) AS count FROM comment c WHERE c.target_type=1 AND c.status=1 AND c.deleted_at IS NULL AND c.target_id IN <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> AND (c.parent_id IS NULL OR EXISTS(SELECT 1 FROM comment p WHERE p.id=c.parent_id AND p.parent_id IS NULL AND p.target_id=c.target_id AND p.target_type=1 AND ((p.status=1 AND p.deleted_at IS NULL) OR (p.status IN(3,4) AND c.community_thread=TRUE)))) GROUP BY c.target_id</script>")
    java.util.List<cn.jualn.miniapp.module.post.bo.PostDiscussionCount> communityCommentCounts(@Param("ids") java.util.Collection<Long> ids);
    @Select("SELECT COUNT(*) FROM like_record WHERE target_type=1 AND target_id=#{id}")
    int communityLikeCount(long id);
    @Select("SELECT COUNT(*) FROM share_record WHERE target_type=1 AND target_id=#{id}") int communityShareCount(long id);
    Post lockPost(long id);

    List<Post> pageOrganizedPosts(cn.jualn.miniapp.module.post.bo.PostFilterBO query);

    int applyEdit(cn.jualn.miniapp.module.post.bo.PostEffectiveEditBO command);

    @Select("SELECT user_id FROM post WHERE id = #{id} AND deleted_at IS NULL")
    Long selectUserIdById(Long id);

    List<PostListBO> selectPostListBatchByIds(@Param("ids") List<Long> ids);

    Long selectViewCountById(@Param("id") Long id);

    List<InteractCountDTO> selectViewCountBatch(@Param("ids") Collection<Long> ids);

    int incrementViewCount(@Param("id") Long id, @Param("delta") Long delta);

    AdminPostStateRow selectAdminStateById(@Param("postId") Long postId);

    int approveAdminReview(@Param("postId") Long postId);

    int rejectAdminReview(@Param("postId") Long postId, @Param("reason") String reason);

    void incrementCommentCount(Long postId);

    void decrementCommentCount(Long postId);

    @Update("UPDATE post SET like_count = #{count} WHERE id = #{postId} AND deleted_at IS NULL")
    void setLikeCount(@Param("postId") Long postId, @Param("count") Long count);
}
