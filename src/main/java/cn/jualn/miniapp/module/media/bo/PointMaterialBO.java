package cn.jualn.miniapp.module.media.bo;

import java.time.LocalDateTime;
import java.util.List;

/** Private POINTS data. Neither a public Attachment nor a client supplied object reference. */
public final class PointMaterialBO {
    private PointMaterialBO() {}
    public record Declaration(String fileName,String kind,String contentType,int sizeBytes) {}
    public record Credentials(String bucket,String region,List<String> objectKeys,String tmpSecretId,
            String tmpSecretKey,String sessionToken,long expiredTime,String customDomain,String uploadId,
            LocalDateTime uploadExpiresAt) {
        public Credentials { objectKeys=List.copyOf(objectKeys); }
        @Override public String toString() { return "PointMaterialCredentials[redacted]"; }
    }
    public record File(String fileId,long ownerUserId,String fileName,String kind,String contentType,int sizeBytes,
            LocalDateTime createdAt,Long applicationId,LocalDateTime unboundExpiresAt) {}
    public record Access(String url,LocalDateTime expiresAt) {
        @Override public String toString() { return "PointMaterialAccess[redacted]"; }
    }
}
