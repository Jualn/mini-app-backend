package cn.jualn.miniapp.module.media.entity;

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
@TableName("media_attachment")
public class MediaAttachment {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Integer targetType;

    private Long targetId;

    private Integer type;

    private String kind;
    private String retention;
    private java.time.LocalDateTime expiresAt;
    private Boolean deleted;

    private Boolean registered;

    private Long registeredBy;

    private String objectKey;

    private String url;

    private String originalName;

    private Integer sortOrder;

    private String contentType;
    private Long sizeBytes;
    private Integer width;
    private Integer height;
    private Double durationSeconds;
    private Boolean verified;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

}
