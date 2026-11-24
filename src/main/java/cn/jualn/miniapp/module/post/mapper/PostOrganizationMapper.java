package cn.jualn.miniapp.module.post.mapper;

import cn.jualn.miniapp.module.post.entity.PostCategoryType;
import cn.jualn.miniapp.module.post.entity.PostDictionary;
import cn.jualn.miniapp.module.post.entity.PostTopicLink;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Collection;

public interface PostOrganizationMapper {
    List<PostDictionary> categories(@Param("ids") Collection<Long> ids);

    List<PostDictionary> topics(@Param("ids") Collection<Long> ids);

    List<PostCategoryType> categoryTypes(@Param("ids") Collection<Long> ids);

    List<PostCategoryType> lockCategoryTypes(long id);

    List<PostTopicLink> links(@Param("ids") Collection<Long> ids);

    PostDictionary lockCategory(long id);

    PostDictionary lockTopic(long id);

    @Delete("DELETE FROM post_topic WHERE post_id=#{id}")
    void clearTopics(long id);

    @Insert("INSERT INTO post_topic(post_id,topic_id,position) VALUES(#{post},#{topic},#{position})")
    void link(@Param("post") long post, @Param("topic") long topic, @Param("position") int position);

    @Insert("INSERT INTO post_category(code,name,status,display_order) VALUES(#{code},#{name},#{status},#{displayOrder})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void createCategory(PostDictionary value);

    @Insert("INSERT INTO topic(code,name,status,display_order) VALUES(#{code},#{name},#{status},#{displayOrder})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void createTopic(PostDictionary value);

    void updateCategory(PostDictionary value);

    void updateTopic(PostDictionary value);

    @Delete("DELETE FROM post_category_type WHERE category_id=#{id} AND post_type=#{type}")
    void removeType(@Param("id") long id, @Param("type") String type);

    @Insert("INSERT INTO post_category_type(category_id,post_type) VALUES(#{id},#{type})")
    void addType(@Param("id") long id, @Param("type") String type);

    Long historicalReference(@Param("id") long id, @Param("type") String type);

    List<PostDictionary> pageCategories(@Param("status") String status, @Param("type") String type, @Param("order") Integer order, @Param("id") Long id, @Param("limit") int limit);

    List<PostDictionary> pageTopics(@Param("status") String status, @Param("order") Integer order, @Param("id") Long id, @Param("limit") int limit);
}
