package cn.jualn.miniapp.module.notify.mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
public interface NotificationMonitoringMapper {
    @Select({"<script>SELECT COUNT(*) FROM notification_delivery WHERE created_at &gt;= #{from} AND created_at &lt; #{until}",
            "<if test='channel != null'>AND channel=#{channel}</if>",
            "AND (notification_type IS NULL OR preference_category IS NULL)</script>"})
    long unknown(@Param("from") LocalDateTime from,@Param("until") LocalDateTime until,@Param("channel") String channel);
    @Select({"<script>SELECT notification_type AS notificationType,channel,status,result_category AS resultCategory,provider_error_code AS errorCode,COUNT(*) AS total",
            "FROM notification_delivery WHERE created_at &gt;= #{from} AND created_at &lt; #{until}",
            "<if test='channel != null'>AND channel=#{channel}</if><if test='category != null'>AND preference_category=#{category}</if>",
            "GROUP BY notification_type,channel,status,result_category,provider_error_code ORDER BY notification_type,channel,status</script>"})
    List<Map<String,Object>> summary(@Param("from") LocalDateTime from,@Param("until") LocalDateTime until,@Param("channel") String channel,@Param("category") String category);
}
