package cn.jualn.miniapp.module.notify.mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
public interface NotificationMonitoringMapper {
    @Select({"<script>SELECT COUNT(*) FROM notification_delivery d JOIN notification n ON n.id=d.notification_id",
            "WHERE n.inbox_generation='V1' AND d.created_at &gt;= #{from} AND d.created_at &lt; #{until}",
            "<if test='channel != null'>AND d.channel=#{channel}</if>",
            "AND (d.notification_type IS NULL OR d.preference_category IS NULL)</script>"})
    long unknown(@Param("from") LocalDateTime from,@Param("until") LocalDateTime until,@Param("channel") String channel);
    @Select({"<script>SELECT d.notification_type AS notificationType,d.channel,d.status,d.result_category AS resultCategory,d.provider_error_code AS errorCode,COUNT(*) AS total",
            "FROM notification_delivery d JOIN notification n ON n.id=d.notification_id",
            "WHERE n.inbox_generation='V1' AND d.created_at &gt;= #{from} AND d.created_at &lt; #{until}",
            "<if test='channel != null'>AND d.channel=#{channel}</if><if test='category != null'>AND d.preference_category=#{category}</if>",
            "GROUP BY d.notification_type,d.channel,d.status,d.result_category,d.provider_error_code ORDER BY d.notification_type,d.channel,d.status</script>"})
    List<Map<String,Object>> summary(@Param("from") LocalDateTime from,@Param("until") LocalDateTime until,@Param("channel") String channel,@Param("category") String category);
}
