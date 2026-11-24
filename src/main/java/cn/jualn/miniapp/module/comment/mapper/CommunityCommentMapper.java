package cn.jualn.miniapp.module.comment.mapper;

import cn.jualn.miniapp.module.comment.entity.Comment;
import org.apache.ibatis.annotations.*;
import java.util.List;

public interface CommunityCommentMapper {
    @Update("UPDATE comment SET first_published_at=COALESCE(first_published_at,NOW(6)) WHERE id=#{id} AND status=1 AND (mentor_attribution IS NOT NULL OR NULLIF(TRIM(mentor_evidence),'') IS NOT NULL)")
    void firstPublished(long id);
    @Insert("INSERT INTO comment_management_history(comment_id,operator_id,action,reason,created_at) VALUES(#{id},#{actor},#{action},#{reason},NOW(6))")
    void history(long id,long actor,String action,String reason);
    @Select("SELECT id,operator_id,action,reason,0 AS revision,created_at FROM comment_management_history WHERE comment_id=#{id} ORDER BY id DESC")
    java.util.List<cn.jualn.miniapp.module.post.bo.PostCommunityBO.Management> historyFor(long id);

    @Update("UPDATE comment SET submission_notice=#{notice} WHERE id=#{id} AND first_published_at IS NULL")
    void notice(long id,String notice);
    @Select("SELECT * FROM comment WHERE id=#{id}") Comment get(long id);
    @Select("SELECT * FROM comment WHERE id=#{id} FOR UPDATE") Comment lock(long id);
    @Select("<script>SELECT c.* FROM comment c WHERE c.target_type=1 AND c.target_id=#{post} AND c.parent_id IS NULL AND ((c.status=1 AND c.deleted_at IS NULL) OR (c.status IN(3,4) AND EXISTS(SELECT 1 FROM comment r WHERE r.parent_id=c.id AND r.target_type=1 AND r.target_id=c.target_id AND r.status=1 AND r.deleted_at IS NULL AND r.community_thread=TRUE)))<if test='after != null'> AND c.id &lt; #{after}</if> ORDER BY c.id DESC LIMIT #{size}</script>")
    List<Comment> roots(long post,Long after,int size);
    @Select("<script>SELECT r.* FROM comment r JOIN comment p ON p.id=r.parent_id WHERE r.target_type=1 AND r.target_id=#{post} AND r.parent_id=#{root} AND r.status=1 AND r.deleted_at IS NULL AND (p.status=1 AND p.deleted_at IS NULL OR p.status IN(3,4) AND r.community_thread=TRUE)<if test='after != null'> AND r.id &lt; #{after}</if> ORDER BY r.id DESC LIMIT #{size}</script>")
    List<Comment> replies(long post,long root,Long after,int size);
    @Select("SELECT COUNT(*) FROM comment r JOIN comment p ON p.id=r.parent_id WHERE r.target_type=1 AND r.target_id=#{post} AND r.parent_id=#{root} AND r.status=1 AND r.deleted_at IS NULL AND (p.status=1 AND p.deleted_at IS NULL OR p.status IN(3,4) AND r.community_thread=TRUE)")
    long replyCount(long post,long root);
    @Select("<script>SELECT c.* FROM comment c JOIN post p ON p.id=c.target_id WHERE c.target_type=1 AND p.post_type IN('NORMAL','MENTOR_QA')<if test='postType != &quot;ALL&quot;'> AND p.post_type=#{postType}</if> AND NOT EXISTS(SELECT 1 FROM post_growth_record g WHERE g.post_id=p.id) AND c.user_id=#{actor} AND c.status!=3<if test='after != null'> AND c.id &lt; #{after}</if> ORDER BY c.id DESC LIMIT #{size}</script>")
    List<Comment> mine(long actor,Long after,int size,String postType);
}
