package cn.jualn.miniapp.module.notify.mapper;

import cn.jualn.miniapp.module.notify.entity.Notification;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import java.time.LocalDateTime;
import java.util.List;

public interface NotificationLifecycleMapper {
    @Select("""
        SELECT n.* FROM notification n WHERE n.inbox_generation='V1' AND n.created_at <= DATE_SUB(#{now}, INTERVAL 90 DAY)
        AND n.source_key IS NOT NULL AND n.source_key REGEXP '[^[:space:]]'
        AND (n.inbox_seq IS NOT NULL OR EXISTS
          (SELECT 1 FROM notification_delivery d WHERE d.notification_id=n.id AND d.channel='IN_APP'))
        AND NOT EXISTS (SELECT 1 FROM notification_delivery d WHERE d.notification_id=n.id
          AND (d.status IN ('PENDING','PROCESSING') OR (d.status='UNKNOWN' AND NOT EXISTS (SELECT 1 FROM notification_delivery_resolution r WHERE r.delivery_id=d.id AND r.resolved_at <= DATE_SUB(#{now},INTERVAL 30 DAY)))))
        ORDER BY n.created_at,n.id LIMIT #{limit}
        """)
    List<Notification> candidates(@Param("now") LocalDateTime now, @Param("limit") int limit);

    @Select("SELECT * FROM notification WHERE id=#{id} FOR UPDATE")
    Notification lock(@Param("id") long id);

    @Update("""
        UPDATE notification_delivery d JOIN notification n ON n.id=d.notification_id
        LEFT JOIN notification_delivery i ON i.notification_id=n.id AND i.channel='IN_APP'
        SET d.status='SKIPPED',d.result_category='validation',d.provider_error_code='notification_expired',
            d.last_error_message='Notification retention window ended'
        WHERE n.inbox_generation='V1' AND d.id=#{id} AND d.status='PENDING' AND n.created_at <= DATE_SUB(#{now}, INTERVAL 90 DAY)
        AND (n.inbox_seq IS NOT NULL OR i.id IS NOT NULL)
        """)
    int expireDelivery(@Param("id") long id, @Param("now") LocalDateTime now);

    @Select("""
        SELECT d.id FROM notification_delivery d JOIN notification n ON n.id=d.notification_id
        WHERE n.inbox_generation='V1' AND d.status='PENDING' AND n.created_at <= DATE_SUB(#{now}, INTERVAL 90 DAY)
        AND (n.inbox_seq IS NOT NULL OR EXISTS
          (SELECT 1 FROM notification_delivery i WHERE i.notification_id=n.id AND i.channel='IN_APP'))
        ORDER BY d.id LIMIT #{limit}
        """)
    List<Long> expiredPending(@Param("now") LocalDateTime now, @Param("limit") int limit);

    @Delete("""
        DELETE FROM notification WHERE inbox_generation='V1' AND id=#{id} AND created_at <= DATE_SUB(#{now}, INTERVAL 90 DAY)
        AND source_key IS NOT NULL AND source_key REGEXP '[^[:space:]]'
        AND EXISTS (SELECT 1 FROM notification_source_receipt r
          WHERE r.source_digest=SHA2(notification.source_key,256) AND r.notification_id=notification.id
            AND r.original_created_at=notification.created_at AND r.disposed_at IS NULL)
        AND NOT EXISTS (SELECT 1 FROM notification_delivery d WHERE d.notification_id=#{id}
          AND (d.status IN ('PENDING','PROCESSING') OR (d.status='UNKNOWN' AND NOT EXISTS (SELECT 1 FROM notification_delivery_resolution r WHERE r.delivery_id=d.id AND r.resolved_at <= DATE_SUB(#{now},INTERVAL 30 DAY)))))
        """)
    int dispose(@Param("id") long id, @Param("now") LocalDateTime now);

    @Update("UPDATE notification_source_receipt SET disposed_at=#{now} WHERE notification_id=#{id}")
    int markDisposed(@Param("id") long id, @Param("now") LocalDateTime now);

    @Update("""
        UPDATE notification n SET n.title='Notification evidence',n.content='',n.content_payload=NULL,n.sender_id=NULL,n.target_id=NULL,n.target_type=NULL
        WHERE n.inbox_generation='V1' AND n.id=#{id} AND n.created_at <= DATE_SUB(#{now}, INTERVAL 180 DAY)
        AND n.source_key IS NOT NULL AND n.source_key REGEXP '[^[:space:]]'
        AND EXISTS (SELECT 1 FROM notification_source_receipt r
          WHERE r.source_digest=SHA2(n.source_key,256) AND r.notification_id=n.id
            AND r.original_created_at=n.created_at AND r.disposed_at IS NULL)
        """)
    int redact(@Param("id") long id, @Param("now") LocalDateTime now);

    @Select("""
        SELECT n.id FROM notification n WHERE n.inbox_generation='V1' AND n.created_at <= DATE_SUB(#{now}, INTERVAL 180 DAY)
        AND n.source_key IS NOT NULL AND n.source_key REGEXP '[^[:space:]]'
        AND (n.content_payload IS NOT NULL OR n.content<>'' OR n.title<>'Notification evidence') AND EXISTS (SELECT 1 FROM notification_delivery d
          WHERE d.notification_id=n.id AND d.status='UNKNOWN')
        AND (n.inbox_seq IS NOT NULL OR EXISTS
          (SELECT 1 FROM notification_delivery i WHERE i.notification_id=n.id AND i.channel='IN_APP'))
        ORDER BY n.created_at,n.id LIMIT #{limit}
        """)
    List<Long> expiredEvidence(@Param("now") LocalDateTime now, @Param("limit") int limit);

    @Update("""
        UPDATE notification_delivery d JOIN notification n ON n.id=d.notification_id
        JOIN notification_source_receipt r ON r.source_digest=SHA2(n.source_key,256)
          AND r.notification_id=n.id AND r.original_created_at=n.created_at AND r.disposed_at IS NULL
        SET d.template_snapshot=NULL WHERE n.inbox_generation='V1' AND n.id=#{id} AND d.status='UNKNOWN'
        AND n.source_key IS NOT NULL AND n.source_key REGEXP '[^[:space:]]'
        """)
    int redactTemplates(@Param("id") long id);

    @Select("""
        SELECT d.id FROM notification_delivery d JOIN notification n ON n.id=d.notification_id
        WHERE n.inbox_generation='V1' AND d.status='PROCESSING' AND n.created_at<=DATE_SUB(#{now},INTERVAL 180 DAY)
        AND (n.inbox_seq IS NOT NULL OR EXISTS
          (SELECT 1 FROM notification_delivery i WHERE i.notification_id=n.id AND i.channel='IN_APP'))
        AND NOT EXISTS (SELECT 1 FROM async_job j WHERE j.job_type='notification.wechat-deliver'
          AND j.subject_type='notification-delivery' AND j.subject_id=CAST(d.id AS CHAR)
          AND j.status='RUNNING' AND j.lease_until>#{now})
        ORDER BY d.id LIMIT #{limit}
        """)
    List<Long> staleProcessing(@Param("now") LocalDateTime now,@Param("limit") int limit);

    @Update("UPDATE notification_delivery SET status='UNKNOWN',result_category='remote',provider_error_code='stale_processing' WHERE id=#{id} AND status='PROCESSING'")
    int unknownStaleProcessing(@Param("id") long id);

    @Select("SELECT COUNT(*) FROM notification WHERE inbox_generation='V1' AND created_at <= DATE_SUB(#{now},INTERVAL 90 DAY) AND inbox_seq IS NOT NULL")
    long backlog(@Param("now") LocalDateTime now);

    @Select("""
        SELECT COUNT(*) FROM notification n
        WHERE n.inbox_generation='V1' AND n.created_at<=DATE_SUB(#{now},INTERVAL 90 DAY)
        AND (n.source_key IS NULL OR NOT (n.source_key REGEXP '[^[:space:]]'))
        AND (n.inbox_seq IS NOT NULL OR EXISTS
          (SELECT 1 FROM notification_delivery d WHERE d.notification_id=n.id AND d.channel='IN_APP'))
        """)
    long unclassifiedBacklog(@Param("now") LocalDateTime now);

    @Select("SELECT * FROM notification_delivery WHERE id=#{id} FOR UPDATE")
    cn.jualn.miniapp.module.notify.entity.NotificationDelivery lockDelivery(@Param("id") long id);

    @Insert("""
        INSERT INTO notification_delivery_resolution(delivery_id,operator_id,conclusion,evidence_ref,resolved_at)
        VALUES(#{id},#{operator},#{conclusion},#{evidence},#{now})
        """)
    void resolution(@Param("id") long id, @Param("operator") long operator,
            @Param("conclusion") String conclusion, @Param("evidence") String evidence, @Param("now") LocalDateTime now);

    @Select("SELECT conclusion,evidence_ref FROM notification_delivery_resolution WHERE delivery_id=#{id}")
    java.util.Map<String,Object> resolutionFor(@Param("id") long id);

    @Update("""
        UPDATE notification_delivery SET status=#{status},result_category='internal',provider_error_code='operator_resolved'
        WHERE id=#{id} AND status='UNKNOWN'
        """)
    int resolve(@Param("id") long id, @Param("status") String status);
}
