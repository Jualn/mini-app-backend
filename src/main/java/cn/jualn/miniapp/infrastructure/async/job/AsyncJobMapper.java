package cn.jualn.miniapp.infrastructure.async.job;

import cn.jualn.miniapp.infrastructure.async.JobStateSnapshot;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AsyncJobMapper {
    int insert(AsyncJob job);

    List<AsyncJob> selectDueForUpdate(@Param("now") LocalDateTime now, @Param("limit") int limit);

    int claim(@Param("id") long id, @Param("owner") String owner, @Param("leaseUntil") LocalDateTime leaseUntil);

    int renewLease(@Param("id") long id, @Param("owner") String owner,
                   @Param("leaseSeconds") int leaseSeconds);

    int lockOwnedAttempt(@Param("id") long id, @Param("owner") String owner);

    int markSucceeded(@Param("id") long id, @Param("owner") String owner, @Param("now") LocalDateTime now);

    int markRetry(@Param("id") long id, @Param("owner") String owner, @Param("nextRunAt") LocalDateTime nextRunAt,
                  @Param("category") String category, @Param("error") String error);

    int markDead(@Param("id") long id, @Param("owner") String owner, @Param("category") String category,
                 @Param("error") String error, @Param("now") LocalDateTime now);

    List<AsyncJob> selectExpiredForUpdate(@Param("now") LocalDateTime now, @Param("limit") int limit);

    int recoverExpired(@Param("id") long id, @Param("owner") String owner, @Param("nextRunAt") LocalDateTime nextRunAt,
                       @Param("dead") boolean dead, @Param("now") LocalDateTime now);

    AsyncJob selectByDedupe(@Param("jobType") String jobType, @Param("dedupeKey") String dedupeKey);

    int cancelPendingByDedupe(@Param("jobType") String jobType, @Param("dedupeKey") String dedupeKey,
                              @Param("now") LocalDateTime now);

    AsyncJob selectById(@Param("id") long id);

    List<AsyncJob> selectDead(@Param("limit") int limit);

    int retryDead(@Param("id") long id, @Param("nextRunAt") LocalDateTime nextRunAt);

    JobStateSnapshot selectStateSnapshot(@Param("now") LocalDateTime now);

    int deleteCompletedBefore(@Param("status") String status, @Param("cutoff") LocalDateTime cutoff,
                              @Param("limit") int limit);
}
