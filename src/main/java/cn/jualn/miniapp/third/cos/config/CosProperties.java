package cn.jualn.miniapp.third.cos.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 腾讯云 COS 配置项。
 *
 * <p>通过 application-*.yaml 的 cos.* 节点注入。</p>
 */
@Getter
@Validated
@RequiredArgsConstructor
@ConfigurationProperties(prefix = "cos")
public class CosProperties {

    /** 云 API 密钥 ID。 */
    @NotBlank
    private final String secretId;

    /** 云 API 密钥 Key。 */
    @NotBlank
    private final String secretKey;

    /** 存储桶名称（bucket）。 */
    @NotBlank
    private final String bucket;

    /** 地域标识，例如 ap-guangzhou。 */
    @NotBlank
    private final String region;

    /** 对外访问 URL 前缀，例如 https://xxx.cos.ap-guangzhou.myqcloud.com。 */
    @NotBlank
    private final String publicUrlPrefix;

    /** 预签名有效期（秒），最小 60 秒。 */
    @Min(300)
    private final long presignExpireSeconds;

    @NotBlank
    private final String customDomain;
}
