package cn.jualn.miniapp.module.learningtask.mapper;

import cn.jualn.miniapp.module.learningtask.entity.LearningAssignment;
import cn.jualn.miniapp.module.learningtask.entity.LearningSubmission;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface LearningTaskLedgerMapper {
    @Getter @Setter
    class Request {
        private String actorScope, operation, requestKey, fingerprint, result,contractRevision;
        private long targetId;
        private LocalDateTime createdAt;
    }
    int claim(Request request);
    Request lockRequest(Request request);
    Request findRequest(Request request);
    String previewReceipt(@Param("actorScope") String actorScope,@Param("task") long task,@Param("preview") long preview);
    int finish(Request request);
    List<LearningAssignment> assignments(@Param("taskId") long taskId);
    List<LearningSubmission> history(@Param("assignmentId") long assignmentId);
    int audit(@Param("taskId") long taskId, @Param("assignmentId") Long assignmentId,
              @Param("actor") long actor, @Param("operation") String operation, @Param("reason") String reason,
              @Param("before") String before, @Param("after") String after, @Param("exceptional") boolean exceptional,
              @Param("now") LocalDateTime now);
}
