package cn.jualn.miniapp.module.activity.mapper;

import cn.jualn.miniapp.module.activity.entity.ActivityEnrollment;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 活动报名数据访问层。
 *
 * <p>提供活动报名相关的数据库操作接口。</p>
 *
 * @author miniapp
 * @since 2026-04-28
 */
@Mapper
public interface ActivityEnrollmentMapper extends BaseMapper<ActivityEnrollment> {

    /**
     * 查询用户对活动的报名记录。
     *
     * <p>用于检查用户是否已报名、报名状态等，避免多余的字段查询。</p>
     *
     * @param activityId 活动ID
     * @param userId 用户ID
     * @return 报名记录，如果不存在则返回null
     */
    @Select("SELECT * FROM activity_enrollment WHERE activity_id = #{activityId} AND user_id = #{userId}")
    ActivityEnrollment selectByActivityIdAndUserId(@Param("activityId") Long activityId, @Param("userId") Long userId);

    @Select("""
            SELECT ae.user_id
            FROM activity_enrollment ae
            LEFT JOIN activity_registration ar
              ON ar.activity_id = ae.activity_id
             AND ar.user_id = ae.user_id
             AND ar.status = 1
            WHERE ae.activity_id = #{activityId}
              AND ae.status = 1
              AND ae.notify_enable = 1
              AND ar.id IS NULL
              AND ae.user_id > #{lastUserId}
            ORDER BY ae.user_id
            LIMIT #{limit}
            """)
    List<Long> selectNotifyEnabledUnregisteredUserIds(@Param("activityId") Long activityId,
                                                       @Param("lastUserId") long lastUserId,
                                                       @Param("limit") int limit);

    @Select("""
            SELECT user_id FROM (
              SELECT user_id FROM activity_enrollment
              WHERE activity_id=#{activityId} AND status=1 AND notify_enable=1
              UNION
              SELECT user_id FROM activity_registration
              WHERE activity_id=#{activityId} AND status=1
            ) recipients
            WHERE user_id &gt; #{lastUserId} AND user_id &lt;= #{upperUserId}
            ORDER BY user_id LIMIT #{limit}
            """)
    List<Long> selectSubscriberOrRegisteredUserIds(@Param("activityId") Long activityId,
                                                    @Param("lastUserId") long lastUserId,
                                                    @Param("upperUserId") long upperUserId,
                                                    @Param("limit") int limit);

    @Select("""
            SELECT COALESCE(MAX(user_id), 0) FROM (
              SELECT user_id FROM activity_enrollment
              WHERE activity_id=#{activityId} AND status=1 AND notify_enable=1
              UNION
              SELECT user_id FROM activity_registration
              WHERE activity_id=#{activityId} AND status=1
            ) recipients
            """)
    long selectSubscriberOrRegisteredUpperBound(@Param("activityId") Long activityId);

        @Select("""
                        <script>
                        SELECT activity_id
                        FROM activity_enrollment
                        WHERE user_id = #{userId}
                            AND status = 1
                            AND activity_id IN
                            <foreach collection='activityIds' item='activityId' open='(' separator=',' close=')'>
                                #{activityId}
                            </foreach>
                        </script>
                        """)
        List<Long> selectEnrolledActivityIds(@Param("userId") Long userId, @Param("activityIds") List<Long> activityIds);
}
