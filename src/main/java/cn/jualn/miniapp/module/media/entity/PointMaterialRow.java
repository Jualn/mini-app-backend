package cn.jualn.miniapp.module.media.entity;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class PointMaterialRow {
    private String fileId,uploadId,fileName,kind,contentType,sourceKey,retainedKey,state;
    private long ownerUserId;
    private int fileIndex,sizeBytes;
    private Long applicationId;
    private LocalDateTime uploadExpiresAt,createdAt,unboundExpiresAt,cleanupAfter;
}
