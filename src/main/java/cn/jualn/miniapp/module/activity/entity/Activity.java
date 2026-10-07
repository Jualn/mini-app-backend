package cn.jualn.miniapp.module.activity.entity;

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
@TableName("activity")
public class Activity {
    private Long contractVersion;
    private Integer lifecycleStatus;
    private String formVersion;
    private String audienceDepartmentIds;
    private String contactsJson;
    private String formSchema;
    private java.time.LocalDateTime cancelledAt;
    private String cancelReason;

    private Integer publishStatus;

    private String summary;
    private String audienceSummary;
    private Integer registrationMode;
    private Integer participantMode;
    private Integer capacityUnit;
    private Integer capacity;
    private Long coverAttachmentId;



    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String title;

    private String location;

    private Integer category;

    private String organizer;

    private Integer audienceScope;

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
