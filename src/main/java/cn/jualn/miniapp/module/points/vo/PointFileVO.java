package cn.jualn.miniapp.module.points.vo;

import cn.jualn.miniapp.module.media.bo.PointMaterialBO;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import java.util.List;

public final class PointFileVO {
    private PointFileVO() {}
    public record Credentials(String bucket,String region,List<String> objectKeys,String tmpSecretId,String tmpSecretKey,String sessionToken,
            @JsonSerialize(using=PointsCounterSerializer.class) long expiredTime,String customDomain,String uploadId,String uploadExpiresAt) {
        @Override public String toString() { return "PointFileCredentials[redacted]"; }
    }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record File(String fileId,String ownerUserId,String fileName,String kind,String contentType,int sizeBytes,String createdAt,String applicationId,String unboundExpiresAt) {}
    public record Access(String url,String expiresAt) {
        @Override public String toString() { return "PointFileAccess[redacted]"; }
    }
    public static Credentials credentials(PointMaterialBO.Credentials c) { return new Credentials(c.bucket(),c.region(),c.objectKeys(),c.tmpSecretId(),c.tmpSecretKey(),c.sessionToken(),c.expiredTime(),c.customDomain(),c.uploadId(),PointsVO.time(c.uploadExpiresAt())); }
    public static File file(PointMaterialBO.File f) { return new File(f.fileId(),Long.toString(f.ownerUserId()),f.fileName(),f.kind(),f.contentType(),f.sizeBytes(),PointsVO.time(f.createdAt()),PointsVO.id(f.applicationId()),PointsVO.time(f.unboundExpiresAt())); }
    public static Access access(PointMaterialBO.Access a) { return new Access(a.url(),PointsVO.time(a.expiresAt())); }
}
