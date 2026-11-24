package cn.jualn.miniapp.module.learningtask.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter @TableName("learning_task_submission")
public class LearningSubmission {
    @TableId(type=IdType.AUTO) private Long id;
    private Long taskId;
    private Long submittedByUserId;
    private String personId;
    private String responsibilityKey;
    private String bindingId;
    private Long assignmentId;
    private Long revision;
    private Long reviewedBy;
    private Integer attempt;
    private String text;
    private String fileIds;
    private String reviewStatus;
    private String reviewReason;
    private LocalDateTime confirmedAt;
    private LocalDateTime submittedAt;
    private LocalDateTime reviewedAt;
}

