package cn.jualn.miniapp.third.cos.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * COS STS 上传凭证。
 */
@Data
@Builder
public class CosUploadCredentialDTO {

    /** 存储桶名称。 */
    private String bucket;

    /** 地域标识。 */
    private String region;

    /** 对象键。 */
    private List<String> objectKeys;

    /** 预签名上传 URL（PUT），STS 流程下不返回。 */
    private String uploadUrl;

    /** 资源访问 URL，STS 流程下不返回。 */
    private String fileUrl;

    /** 凭证过期时间。 */
    private LocalDateTime expireAt;

    // STS 临时凭证字段（当使用 STS 流程时返回）
    private String tmpSecretId;
    private String tmpSecretKey;
    private String sessionToken;
    /** ExpiredTime 字段，单位为 epoch seconds */
    private Long expiredTime;

    private String customDomain;
}
