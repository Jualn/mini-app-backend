package cn.jualn.miniapp.module.notify.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.Collection;
import java.util.List;

public interface NotificationPreferenceOwnerMapper {
    @Select("SELECT COUNT(*) FROM notification_preference_owner " +
            "WHERE user_id=#{userId} AND owner='CANONICAL'")
    int isCanonical(@Param("userId") long userId);

    List<Long> selectCanonicalUserIds(@Param("userIds") Collection<Long> userIds);

    @Insert("INSERT INTO notification_preference_owner(user_id, owner, migrated_at) " +
            "VALUES(#{userId}, 'CANONICAL', CURRENT_TIMESTAMP(3))")
    int insertCanonical(@Param("userId") long userId);
}
