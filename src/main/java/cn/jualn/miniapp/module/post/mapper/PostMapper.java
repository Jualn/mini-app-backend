package cn.jualn.miniapp.module.post.mapper;

import cn.jualn.miniapp.module.post.bo.PostListBO;
import cn.jualn.miniapp.module.post.entity.Post;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface PostMapper extends BaseMapper<Post> {

    @Select("SELECT user_id FROM post WHERE id = #{id} AND deleted_at IS NULL")
    Long selectUserIdById(Long id);


    List<PostListBO> selectPostListBatchByIds(@Param("ids") List<Long> ids);

    @Update("UPDATE post SET view_count = view_count + 1 WHERE id = #{postId} AND deleted_at IS NULL")
    void incrementViewCount(Long postId);

    @Update("UPDATE post SET like_count = like_count + 1 WHERE id = #{postId} AND deleted_at IS NULL")
    void incrementLikeCount(Long postId);

    @Update("UPDATE post SET like_count =  like_count - 1 WHERE id = #{postId} AND deleted_at IS NULL")
    void decrementLikeCount(Long postId);

    @Update("UPDATE post SET share_count = share_count + 1 WHERE id = #{postId} AND deleted_at IS NULL")
    void incrementShareCount(Long postId);

    @Update("UPDATE post SET comment_count = comment_count + 1 WHERE id = #{postId} AND deleted_at IS NULL")
    void incrementCommentCount(Long postId);

    @Update("UPDATE post SET comment_count = comment_count - 1 WHERE id = #{postId} AND deleted_at IS NULL")
    void decrementCommentCount(Long postId);

    @Update("UPDATE post SET like_count = #{count} WHERE id = #{postId} AND deleted_at IS NULL")
    void setLikeCount(@Param("postId") Long postId, @Param("count") Long count);

    @Update("UPDATE post SET view_count = #{count} WHERE id = #{postId} AND deleted_at IS NULL")
    void setViewCount(@Param("postId") Long postId, @Param("count") Long count);
}
