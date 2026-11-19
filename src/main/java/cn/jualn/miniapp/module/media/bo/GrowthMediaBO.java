package cn.jualn.miniapp.module.media.bo;

import java.time.OffsetDateTime;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonInclude;

public final class GrowthMediaBO {
    private GrowthMediaBO() {}
    public static final class NumericSerializer extends com.fasterxml.jackson.databind.ser.std.NumberSerializer {
        public NumericSerializer(){super(Long.class);}
    }
    public record Declaration(String kind,String originalName,String contentType,long sizeBytes) {
        @com.fasterxml.jackson.annotation.JsonAnySetter public void unknown(String key,Object value){throw cn.jualn.miniapp.common.exception.ContractProblemException.validation(new cn.jualn.miniapp.common.exception.ContractProblemException.Violation("body","/files/"+key,"UNKNOWN_FIELD","Unknown growth media field"));}
    }
    public record UploadCommand(String organizationId,String recordType,List<Declaration> files,Long postId) {}
    public record Scope(long user,String person,String organization,String type,Long post) {}
    public record Credentials(String bucket,String region,List<String> objectKeys,String tmpSecretId,String tmpSecretKey,String sessionToken,@com.fasterxml.jackson.databind.annotation.JsonSerialize(using=NumericSerializer.class) long expiredTime,String customDomain) {
        @Override public String toString(){return "GrowthMediaCredentials[redacted]";}
    }
    public record Upload(String uploadId,OffsetDateTime expiresAt,OffsetDateTime registrationExpiresAt,Credentials credentials) {
        @Override public String toString(){return "GrowthMediaUpload[redacted]";}
    }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record File(long mediaId,String kind,String originalName,String contentType,@com.fasterxml.jackson.databind.annotation.JsonSerialize(using=NumericSerializer.class) long sizeBytes,String safety,String contentUrl,OffsetDateTime unboundExpiresAt) {}
}
