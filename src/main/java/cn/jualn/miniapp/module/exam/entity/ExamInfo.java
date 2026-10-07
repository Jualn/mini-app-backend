package cn.jualn.miniapp.module.exam.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("public_event")
public class ExamInfo {
    private Long contractVersion;
    private Integer lifecycleStatus;
    private String sourceName;
    private String sourceUrl;
    private String contactsJson;
    private Long coverAttachmentId;
    private java.time.LocalDateTime cancelledAt;
    private String cancelReason;

    private Integer publishStatus;

    private String summary;
    private Integer eventType;
    private String editionLabel;


    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String title;

    private String officialUrl;

    private Integer commentCount;

    private Integer likeCount;

    private Integer viewCount;

    private LocalDateTime publishedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    private LocalDateTime deletedAt;

}
