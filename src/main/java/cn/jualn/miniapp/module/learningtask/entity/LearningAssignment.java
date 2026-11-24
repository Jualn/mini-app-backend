package cn.jualn.miniapp.module.learningtask.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter @TableName("learning_task_assignment")
public class LearningAssignment {
    @TableId(type=IdType.AUTO) private Long id;
    private Long taskId;
    private Long userId;
    private String subjectType;
    private String personId;
    private Long confirmedByUserId;
    private String confirmedBindingId;
    private Long revision;
    private Long assignedBy;
    private Long latestSubmissionId;
    private Long revokedBy;
    private String allocationStatus;
    private String progress;
    private String revocationReason;
    private Boolean onTimeFirstSubmission;
    private LocalDateTime assignedAt;
    private LocalDateTime confirmedAt;
    private LocalDateTime firstSubmittedAt;
    private LocalDateTime supplementDeadline;
    private LocalDateTime completedAt;
    private LocalDateTime revokedAt;
}

