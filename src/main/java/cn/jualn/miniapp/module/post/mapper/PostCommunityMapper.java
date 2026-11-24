package cn.jualn.miniapp.module.post.mapper;

import cn.jualn.miniapp.module.post.bo.PostCommunityBO.Management;
import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;
import java.util.List;

public interface PostCommunityMapper {
    record Receipt(String fingerprint, Long targetId) { }
    record SavedRow(Long postId, LocalDateTime time) { }
    @Insert("INSERT INTO post_creation_receipt(actor_id,operation,request_key,fingerprint,created_at) VALUES(#{actor},#{operation},#{key},#{hash},NOW(6)) ON DUPLICATE KEY UPDATE actor_id=actor_id")
    void reserve(long actor, String operation, String key, String hash);
    @Select("SELECT fingerprint,target_id FROM post_creation_receipt WHERE actor_id=#{actor} AND operation=#{operation} AND request_key=#{key} FOR UPDATE")
    Receipt lockReceipt(long actor, String operation, String key);
    @Select("SELECT fingerprint,target_id FROM post_creation_receipt WHERE actor_id=#{actor} AND operation=#{operation} AND request_key=#{key}")
    Receipt receipt(long actor, String operation, String key);
    @Update("UPDATE post_creation_receipt SET target_id=#{id} WHERE actor_id=#{actor} AND operation=#{operation} AND request_key=#{key} AND target_id IS NULL")
    int complete(long actor, String operation, String key, long id);
    @Insert("INSERT INTO post_management_history(post_id,operator_id,action,reason,revision,created_at) VALUES(#{post},#{actor},#{action},#{reason},#{revision},NOW(6))")
    void history(long post, long actor, String action, String reason, long revision);
    @Select("SELECT id,operator_id,action,reason,revision,created_at FROM post_management_history WHERE post_id=#{post} ORDER BY id DESC")
    List<Management> historyFor(long post);
    @Insert("INSERT INTO post_favorite(actor_id,post_id,favorited_at) VALUES(#{actor},#{post},NOW(6)) ON DUPLICATE KEY UPDATE actor_id=actor_id")
    void favorite(long actor, long post);
    @Delete("DELETE FROM post_favorite WHERE actor_id=#{actor} AND post_id=#{post}")
    void unfavorite(long actor, long post);
    @Select("SELECT COUNT(*)>0 FROM post_favorite WHERE actor_id=#{actor} AND post_id=#{post}")
    boolean favorited(long actor, long post);
    @Select("<script>SELECT f.post_id,f.favorited_at AS time FROM post_favorite f JOIN post p ON p.id=f.post_id WHERE NOT EXISTS(SELECT 1 FROM post_growth_record g WHERE g.post_id=p.id) AND f.actor_id=#{actor}<if test='postType != &quot;ALL&quot;'> AND p.post_type=#{postType}</if><if test='time != null'> AND (favorited_at &lt; #{time} OR (favorited_at=#{time} AND p.id &lt; #{id}))</if> ORDER BY favorited_at DESC,p.id DESC LIMIT #{size}</script>")
    List<SavedRow> favorites(long actor, LocalDateTime time, Long id, int size,String postType);
    @Select("<script>SELECT h.post_id,h.last_viewed_at AS time FROM post_view_history h JOIN post p ON p.id=h.post_id WHERE NOT EXISTS(SELECT 1 FROM post_growth_record g WHERE g.post_id=p.id) AND h.actor_id=#{actor}<if test='postType != &quot;ALL&quot;'> AND p.post_type=#{postType}</if> AND last_viewed_at &gt; #{cutoff}<if test='time != null'> AND (last_viewed_at &lt; #{time} OR (last_viewed_at=#{time} AND p.id &lt; #{id}))</if> ORDER BY last_viewed_at DESC,p.id DESC LIMIT #{size}</script>")
    List<SavedRow> views(long actor, LocalDateTime cutoff, LocalDateTime time, Long id, int size,String postType);
    @Insert("INSERT INTO post_view_history(actor_id,post_id,last_viewed_at) VALUES(#{actor},#{post},#{now}) ON DUPLICATE KEY UPDATE last_viewed_at=GREATEST(last_viewed_at,#{now})")
    void viewed(long actor, long post, LocalDateTime now);
    @Delete("DELETE FROM post_view_history WHERE actor_id=#{actor}")
    void clearViews(long actor);
}
