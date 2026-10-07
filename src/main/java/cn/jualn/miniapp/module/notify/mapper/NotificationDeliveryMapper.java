package cn.jualn.miniapp.module.notify.mapper;

import cn.jualn.miniapp.module.notify.entity.NotificationDelivery;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

public interface NotificationDeliveryMapper extends BaseMapper<NotificationDelivery> {
    @Select("SELECT * FROM notification_delivery WHERE id = #{id}")
    NotificationDelivery selectDelivery(@Param("id") long id);

    @Update("UPDATE notification_delivery SET status='PROCESSING', result_category=NULL, " +
            "provider_error_code=NULL, last_error_message=NULL " +
            "WHERE id=#{id} AND status='PENDING'")
    int startProcessing(@Param("id") long id);

    @Update("UPDATE notification_delivery SET status='PROCESSING', attempt_count=attempt_count+1, " +
            "last_attempt_at=#{now}, result_category=NULL, provider_error_code=NULL, last_error_message=NULL " +
            "WHERE id=#{id} AND status='PENDING'")
    int startProviderAttempt(@Param("id") long id, @Param("now") LocalDateTime now);

    @Update("UPDATE notification_delivery SET attempt_count=attempt_count+1, last_attempt_at=#{now} " +
            "WHERE id=#{id} AND status='PROCESSING'")
    int recordProviderAttempt(@Param("id") long id, @Param("now") LocalDateTime now);

    @Update("UPDATE notification_delivery SET status='DELIVERED', provider_message_id=#{messageId}, " +
            "result_category=NULL, provider_error_code=NULL, last_error_message=NULL, delivered_at=#{now} " +
            "WHERE id=#{id} AND status='PROCESSING'")
    int markDelivered(@Param("id") long id, @Param("messageId") String messageId,
                      @Param("now") LocalDateTime now);

    @Update("UPDATE notification_delivery SET status=#{status}, result_category=#{category}, " +
            "provider_error_code=#{errorCode}, last_error_message=#{message} " +
            "WHERE id=#{id} AND status IN ('PENDING','PROCESSING')")
    int markTerminal(@Param("id") long id, @Param("status") String status,
                     @Param("category") String category, @Param("errorCode") String errorCode,
                     @Param("message") String message);

    @Update("UPDATE notification_delivery SET status='PENDING', result_category=#{category}, " +
            "provider_error_code=#{errorCode}, last_error_message=#{message} " +
            "WHERE id=#{id} AND status='PROCESSING'")
    int markRetryable(@Param("id") long id, @Param("category") String category,
                      @Param("errorCode") String errorCode, @Param("message") String message);

    @Update("""
            UPDATE notification_delivery d
            JOIN (
              SELECT ordered_candidates.delivery_id
              FROM (
                SELECT d2.id AS delivery_id
                FROM notification_delivery d2
                JOIN async_job j ON j.job_type='notification.wechat-deliver'
                  AND j.schema_version=2
                  AND j.subject_type='notification-delivery'
                  AND CAST(j.subject_id AS UNSIGNED)=d2.id
                WHERE j.status='DEAD' AND d2.status IN ('PENDING','PROCESSING')
                GROUP BY d2.id
                ORDER BY MIN(j.dead_at), MIN(j.id)
                LIMIT #{limit}
              ) ordered_candidates
            ) candidates ON candidates.delivery_id=d.id
            SET d.result_category=CASE WHEN d.status='PROCESSING' THEN 'remote' ELSE 'internal' END,
                d.status=CASE WHEN d.status='PROCESSING' THEN 'UNKNOWN' ELSE 'FAILED' END,
                d.provider_error_code='job_dead',
                d.last_error_message='Delivery job reached DEAD without a terminal delivery result'
            WHERE d.status IN ('PENDING','PROCESSING')
            """)
    int repairDeadJobResults(@Param("limit") int limit);
}
