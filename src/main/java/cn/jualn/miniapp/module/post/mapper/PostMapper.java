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

    @Select("SELECT user_id FROM post WHERE id = #{id} AND deleted_at IS NULL")
    Long selectUserIdById(Long id);

    List<PostListBO> selectPostListBatchByIds(@Param("ids") List<Long> ids);

    Long selectViewCountById(@Param("id") Long id);

    List<InteractCountDTO> selectViewCountBatch(@Param("ids") Collection<Long> ids);

    int incrementViewCount(@Param("id") Long id, @Param("delta") Long delta);

    @Update("UPDATE post SET comment_count = comment_count + 1 WHERE id = #{postId} AND deleted_at IS NULL")
    void incrementCommentCount(Long postId);

    @Update("UPDATE post SET comment_count = comment_count - 1 WHERE id = #{postId} AND deleted_at IS NULL")
    void decrementCommentCount(Long postId);

    @Update("UPDATE post SET like_count = #{count} WHERE id = #{postId} AND deleted_at IS NULL")
    void setLikeCount(@Param("postId") Long postId, @Param("count") Long count);
}
