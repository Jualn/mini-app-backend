package cn.jualn.miniapp.module.notify.mapper;

import cn.jualn.miniapp.module.notify.entity.NotificationPreference;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Collection;

public interface NotificationPreferenceMapper {
    @Select("SELECT user_id, category, channel, enabled, source FROM notification_preference " +
            "WHERE user_id = #{userId} ORDER BY category, channel")
    List<NotificationPreference> selectByUserId(@Param("userId") long userId);

    List<NotificationPreference> selectByUserIds(@Param("userIds") Collection<Long> userIds);

    @Select("SELECT COUNT(*) FROM notification_preference WHERE user_id=#{userId}")
    int countByUserId(@Param("userId") long userId);

    @Select("SELECT enabled FROM notification_preference WHERE user_id=#{userId} " +
            "AND category=#{category} AND channel=#{channel}")
    Boolean selectEnabled(@Param("userId") long userId, @Param("category") String category,
                          @Param("channel") String channel);

    @Insert("INSERT INTO notification_preference(user_id, category, channel, enabled, source) " +
            "VALUES(#{userId}, #{category}, #{channel}, #{enabled}, #{source}) " +
            "ON DUPLICATE KEY UPDATE enabled=VALUES(enabled), source=VALUES(source), updated_at=CURRENT_TIMESTAMP(3)")
    int upsert(@Param("userId") long userId, @Param("category") String category,
               @Param("channel") String channel, @Param("enabled") boolean enabled,
               @Param("source") String source);

    @Insert("INSERT INTO notification_preference(user_id, category, channel, enabled, source) " +
            "VALUES(#{userId}, #{category}, #{channel}, #{enabled}, #{source})")
    int insert(@Param("userId") long userId, @Param("category") String category,
               @Param("channel") String channel, @Param("enabled") boolean enabled,
               @Param("source") String source);

    @Delete("DELETE FROM notification_preference WHERE user_id=#{userId} " +
            "AND category=#{category} AND channel=#{channel}")
    int delete(@Param("userId") long userId, @Param("category") String category,
               @Param("channel") String channel);
}
