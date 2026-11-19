package cn.jualn.miniapp.module.media.entity;
import java.time.LocalDateTime;
import lombok.Data;
@Data
public class LearningMaterialRow {
    private Long fileId,assignmentId,ownerUserId,submissionId,safetyAuditLogId;
    private String uploadId,fileName,kind,contentType,sourceKey,retainedKey,state;
    private int fileIndex,sizeBytes;
    private LocalDateTime createdAt,uploadExpiresAt,unboundExpiresAt,cleanupAfter;
}
