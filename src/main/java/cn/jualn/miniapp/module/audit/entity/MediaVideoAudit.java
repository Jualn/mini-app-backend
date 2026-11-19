package cn.jualn.miniapp.module.audit.entity;

import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;
import lombok.Data;

/** Immutable resource and policy snapshot; results never publish business content. */
@Data @TableName("media_video_audit")
public class MediaVideoAudit {
    @TableId(type=IdType.AUTO) private Long id;
    private Long attachmentId;
    private String objectKey;
    private Long sizeBytes;
    private String policyKey;
    private String bucket;
    private String region;
    private String bizType;
    private Integer snapshotIntervalSeconds;
    private Long operatorId;
    private String state;
    private String providerJobId;
    private Integer pollSequence;
    private LocalDateTime deadlineAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
