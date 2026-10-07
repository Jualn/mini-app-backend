package cn.jualn.miniapp.infrastructure.async.outbox;

import cn.jualn.miniapp.infrastructure.async.OutboxStateSnapshot;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface OutboxMapper {
    int insert(OutboxEvent event);
    List<OutboxEvent> selectDueForUpdate(@Param("now") LocalDateTime now, @Param("limit") int limit);
    int claim(@Param("id") long id, @Param("owner") String owner, @Param("leaseUntil") LocalDateTime leaseUntil);
    int markPublished(@Param("id") long id, @Param("owner") String owner, @Param("now") LocalDateTime now);
    int markRetry(@Param("id") long id, @Param("owner") String owner, @Param("next") LocalDateTime next,
                  @Param("category") String category, @Param("error") String error);
    int markDead(@Param("id") long id, @Param("owner") String owner, @Param("category") String category,
                 @Param("error") String error, @Param("now") LocalDateTime now);
    OutboxStateSnapshot selectStateSnapshot(@Param("now") LocalDateTime now);
    int deletePublishedBefore(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);
    int deleteDeadBefore(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);
    OutboxEvent selectById(@Param("id") long id);
    List<OutboxEvent> selectDead(@Param("limit") int limit);
    int retryDead(@Param("id") long id, @Param("next") LocalDateTime next);
}
