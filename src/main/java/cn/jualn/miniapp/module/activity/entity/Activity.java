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

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String title;

    private String content;

    private String location;

    private Integer category;

    private String organizer;

    private Integer audienceScope;

    private String contactInfo;

    private String joinMethod;

    private String qrcodeUrl;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private LocalDateTime enrollDeadline;

    private Integer maxParticipants;

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
