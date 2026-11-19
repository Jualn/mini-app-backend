package cn.jualn.miniapp.module.learningtask.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("learning_task")
public class LearningTask {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long articleId;
    private Long revision;
    private Long deadlineRevision;
    private Long createdBy;
    private Long cancelledBy;
    private String title;
    private String description;
    private String requirements;
    private String audience;
    private String managementTarget;
    private String status;
    private String cancellationReason;
    private Boolean reviewRequired;
    private LocalDateTime firstDeadline;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime publishedAt;
    private LocalDateTime closedAt;
    private LocalDateTime cancelledAt;
}

