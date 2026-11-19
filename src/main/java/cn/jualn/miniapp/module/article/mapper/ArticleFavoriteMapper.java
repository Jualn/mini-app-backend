package cn.jualn.miniapp.module.article.mapper;
import org.apache.ibatis.annotations.*;
import java.util.List;
import java.time.LocalDateTime;
import cn.jualn.miniapp.module.article.entity.ArticleFavorite;
@Mapper
public interface ArticleFavoriteMapper {
 @Select("SELECT EXISTS(SELECT 1 FROM article_favorite WHERE user_id=#{user} AND article_id=#{article})") boolean exists(@Param("user") long user,@Param("article") long article);
 @Insert("INSERT INTO article_favorite(user_id,article_id,favorited_at) VALUES(#{user},#{article},#{at}) ON DUPLICATE KEY UPDATE article_id=VALUES(article_id)") int add(@Param("user") long user,@Param("article") long article,@Param("at") LocalDateTime at);
 @Delete("DELETE FROM article_favorite WHERE user_id=#{user} AND article_id=#{article}") int remove(@Param("user") long user,@Param("article") long article);
 @Select("<script>SELECT article_id,favorited_at FROM article_favorite WHERE user_id=#{user} <if test='at != null'>AND (favorited_at &lt; #{at} OR (favorited_at=#{at} AND article_id &lt; #{id}))</if> ORDER BY favorited_at DESC,article_id DESC LIMIT #{size}</script>")
 List<ArticleFavorite> page(@Param("user") long user,@Param("at") LocalDateTime at,@Param("id") Long id,@Param("size") int size);
}
