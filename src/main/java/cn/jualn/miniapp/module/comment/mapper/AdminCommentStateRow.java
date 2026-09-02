package cn.jualn.miniapp.module.comment.mapper;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AdminCommentStateRow {

    private Long id;
    private Integer targetType;
    private Long targetId;
    private Long parentId;
    private Integer status;
    private Integer auditStatus;
    private LocalDateTime deletedAt;
}
