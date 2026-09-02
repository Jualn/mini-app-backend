package cn.jualn.miniapp.module.post.mapper;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AdminPostStateRow {

    private Long id;
    private Integer status;
    private Integer auditStatus;
    private Boolean isPinned;
    private Boolean isFeatured;
    private LocalDateTime deletedAt;
}
