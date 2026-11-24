package cn.jualn.miniapp.module.comment.entity;

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
@TableName("comment")
public class Comment {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Integer targetType;

    private Long targetId;

    private Long userId;

    private Long parentId;

    private Boolean communityThread;
    private Long replyToUid;
    private Long replyToCommentId;
    private String mentorEvidence;
    private String questionVersion;
    private String mentorAttribution;
    private String submissionNotice;
    private LocalDateTime firstPublishedAt;

    private String content;

    private String imageUrl;

    private String imageObjectKey;

    private Integer status;

    private Integer auditStatus;

    private Integer likeCount;

    private Integer replyCount;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    private LocalDateTime deletedAt;

}
