package cn.jualn.miniapp.module.content.mapper;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
public class AdminContentDetailRow extends AdminContentListRow {
    private String content;
    private String rejectReason;
    private String imageUrl;
    private Integer targetType;
    private Long targetId;
    private Long parentId;
    private Integer authorStatus;
    private LocalDateTime authorCreatedAt;
    private Long authorPublishedCount;
    private Long authorViolationCount;
}
