package cn.jualn.miniapp.module.exam.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("exam_info")
public class ExamInfo {
    private String organizer;
    private String location;
    private Integer audienceScope;
    private String audienceSummary;
    private String contactName;
    private String contactPhone;
    private Integer registrationMode;
    private Integer participantMode;
    private Integer capacity;
    private Integer capacityUnit;
    private Long coverAttachmentId;
    private java.time.LocalDateTime cancelledAt;
    private String cancelReason;

    private Integer publishStatus;

    private Integer startPrecision;
    private Integer endPrecision;
    private String timeDescription;
    private Integer registrationStartPrecision;
    private Integer registrationEndPrecision;
    private java.time.LocalDateTime startTime;
    private java.time.LocalDateTime endTime;

    private String summary;
    private Integer eventType;
    private String editionLabel;


    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String title;

    private Integer category;

    private String content;

    private LocalDateTime registrationStart;

    private LocalDateTime registrationEnd;

    private LocalDate examDate;

    private LocalDate examDateEnd;

    private String officialUrl;

    private Integer status;

    private Integer auditStatus;

    private String rejectReason;

    private Boolean isPinned;

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
