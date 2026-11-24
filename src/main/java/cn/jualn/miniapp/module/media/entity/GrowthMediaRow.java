package cn.jualn.miniapp.module.media.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class GrowthMediaRow {
    private String uploadId,personId,organizationId,recordType,sourceKey,originalName,contentType;
    private int fileIndex;
    private long ownerUserId,sizeBytes;
    private Long editingPostId,mediaId,boundPostId;
    private LocalDateTime createdAt,expiresAt,registeredAt,unboundExpiresAt;
}
