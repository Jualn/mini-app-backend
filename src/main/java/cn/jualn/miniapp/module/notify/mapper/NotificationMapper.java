package cn.jualn.miniapp.module.notify.mapper;

import cn.jualn.miniapp.module.notify.entity.Notification;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

public interface NotificationMapper extends BaseMapper<Notification> {

    default Integer selectIsReadByIdAndUserId(Long id, Long userId) { return selectCanonicalReadState(id, userId); }

    List<Notification> selectCanonicalInbox(
            @Param("userId") long userId,
            @Param("cursorCreatedAt") LocalDateTime cursorCreatedAt,
            @Param("cursorId") Long cursorId,
            @Param("typeCodes") List<Integer> typeCodes,
            @Param("isRead") Boolean isRead,
            @Param("limit") int limit);

    List<Notification> selectLegacyInbox(
            @Param("userId") long userId,
            @Param("lastId") Long lastId,
            @Param("typeCode") Integer typeCode,
            @Param("isRead") Integer isRead,
            @Param("limit") int limit);

    Long countCanonicalUnread(@Param("userId") long userId);

    Integer selectCanonicalReadState(@Param("id") long id, @Param("userId") long userId);

    int markCanonicalRead(@Param("id") long id, @Param("userId") long userId);

    int markAllCanonicalRead(@Param("userId") long userId);

    int ensureInboxCounter(@Param("userId") long userId);
    long lockInboxHead(@Param("userId") long userId);
    long selectInboxHead(@Param("userId") long userId);
    String selectUnreadProjectionVersion(@Param("userId") long userId);
    int advanceReadVersion(@Param("userId") long userId);
    Long selectHistoricalStartOccurrence(@Param("userId") long userId, @Param("targetType") int targetType,
            @Param("targetId") long targetId, @Param("anchor") String anchor);
    Long selectInboxSequence(@Param("id") long id, @Param("userId") long userId);
    int assignInboxSequence(@Param("id") long id, @Param("userId") long userId, @Param("sequence") long sequence);
    int advanceInboxHead(@Param("userId") long userId, @Param("previous") long previous, @Param("next") long next);
    Notification selectOwned(@Param("id") long id, @Param("userId") long userId);
    List<Long> lockOwnedIds(@Param("userId") long userId, @Param("ids") List<Long> ids);
    int markOwnedRead(@Param("userId") long userId, @Param("ids") List<Long> ids);
    int markReadThrough(@Param("userId") long userId, @Param("boundary") long boundary);
    List<Notification> selectStructuredInbox(@Param("userId") long userId, @Param("head") long head,
            @Param("before") Long before, @Param("typeCodes") List<Integer> typeCodes,
            @Param("systemCategory") boolean systemCategory, @Param("isRead") Boolean isRead, @Param("limit") int limit);
    long countUnreadThrough(@Param("userId") long userId, @Param("head") long head);
    long countNewThrough(@Param("userId") long userId, @Param("after") long after, @Param("head") long head);
    Notification selectLatestThrough(@Param("userId") long userId, @Param("after") long after, @Param("head") long head);
    int deleteOwnedInbox(@Param("id") long id, @Param("userId") long userId);
    Notification selectBySourceForUpdate(@Param("sourceKey") String sourceKey);
    boolean isExpiredInApp(@Param("id") long id);
    boolean canEnterInbox(@Param("id") long id);

    @org.apache.ibatis.annotations.Insert("INSERT INTO notification_source_receipt(source_digest,identity_digest,content_digest,notification_id,original_created_at) VALUES(#{source},#{identity},#{content},0,#{created}) ON DUPLICATE KEY UPDATE source_digest=source_digest")
    void claimSource(@Param("source") String source, @Param("identity") String identity,
            @Param("content") String content, @Param("created") LocalDateTime created);
    @Select("SELECT * FROM notification_source_receipt WHERE source_digest=#{source} FOR UPDATE")
    cn.jualn.miniapp.module.notify.entity.NotificationSourceReceipt lockSource(@Param("source") String source);
    @org.apache.ibatis.annotations.Update("UPDATE notification_source_receipt SET notification_id=#{id},original_created_at=#{created} WHERE source_digest=#{source} AND notification_id=0")
    int finishSource(@Param("source") String source, @Param("id") long id, @Param("created") LocalDateTime created);
}
