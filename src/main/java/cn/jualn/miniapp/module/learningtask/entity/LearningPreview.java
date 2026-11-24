package cn.jualn.miniapp.module.learningtask.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter @TableName("learning_task_preview")
public class LearningPreview {
    @TableId(type=IdType.AUTO) private Long id;
    private Long taskId;
    private Long taskRevision;
    private String taskEtag;
    private Long actorId;
    private String purpose;
    private String audience;
    private String recipients;
    private String dependencyVersion;
    private Integer newAssignmentCount;
    private Integer alreadyAssignedCount;

    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;
}

