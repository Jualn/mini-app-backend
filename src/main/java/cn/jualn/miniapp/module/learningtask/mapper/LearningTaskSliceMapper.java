package cn.jualn.miniapp.module.learningtask.mapper;

import cn.jualn.miniapp.module.learningtask.entity.LearningAssignment;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface LearningTaskSliceMapper {
    Long assignmentTask(long assignment);
    List<LearningAssignment> mine(@Param("user") long user,@Param("progress") String progress,@Param("before") LocalDateTime before,
                                 @Param("beforeId") Long beforeId,@Param("limit") int limit);
    List<Long> page(@Param("task") long task,@Param("allocation") String allocation,@Param("progress") String progress,
                    @Param("user") Long user,@Param("offset") long offset,@Param("limit") int limit);
    int count(@Param("task") long task,@Param("allocation") String allocation,@Param("progress") String progress,@Param("user") Long user);
    int submissions(@Param("task") long task,@Param("resubmissions") boolean resubmissions);
    List<Long> submissionHistory(@Param("assignment") long assignment,@Param("offset") long offset,@Param("limit") int limit);
    int submissionHistoryCount(@Param("assignment") long assignment);
    List<Long> reviewQueue(@Param("scope") cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.ReadScope scope,@Param("actionable") boolean actionable,@Param("task") Long task,@Param("status") String status,
                           @Param("offset") long offset,@Param("limit") int limit);
    int reviewQueueCount(@Param("scope") cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.ReadScope scope,@Param("actionable") boolean actionable,@Param("task") Long task,@Param("status") String status);
}
