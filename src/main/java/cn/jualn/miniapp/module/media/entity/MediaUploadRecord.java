package cn.jualn.miniapp.module.media.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("media_upload_record")
public class MediaUploadRecord {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String objectKey;
    private Long userId;
    private Integer targetType;
    private Integer status;
    private Long boundTargetId;
    private LocalDateTime cleanupAfter;
    private Integer retryCount;
    private String lastError;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
