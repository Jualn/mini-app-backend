package cn.jualn.miniapp.third.cos.service;

import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.exception.ExternalServiceException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.third.cos.client.CosClient;
import cn.jualn.miniapp.third.cos.config.CosProperties;
import cn.jualn.miniapp.third.cos.dto.CosUploadCredentialDTO;
import com.tencent.cloud.Response;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * COS 业务服务。
 *
 * <p>负责将业务参数转换为前端可直接使用的上传凭证对象。</p>
 */
@Service
@RequiredArgsConstructor
public class CosService {

    private final CosClient cosClient;
    private final CosProperties cosProperties;
    private final ObjectMapper objectMapper;

    /**
     * 生成前端直传 COS 的 STS 上传凭证。
     *
     * @param objectKeys COS 对象键
     * @return 上传凭证
     */
    public CosUploadCredentialDTO generateUploadCredential(List<String> objectKeys) {
        if (objectKeys == null || objectKeys.isEmpty()) {
            throw new BusinessException(ResultCode.INVALID_OPERATION, "objectKeys 不能为空");
        }
        // 调用 SDK 的 STS 接口获取临时凭证
        String prefix = objectKeys.get(0).substring(0, objectKeys.get(0).lastIndexOf("/") + 1);
        Response resp = cosClient.getCredential(prefix);
        // Response.toString() 返回 JSON 字符串，解析出 credentials
        CosUploadCredentialDTO.CosUploadCredentialDTOBuilder builder = CosUploadCredentialDTO.builder()
                .bucket(cosProperties.getBucket())
                .region(cosProperties.getRegion())
                .customDomain(cosProperties.getCustomDomain())
                .objectKeys(objectKeys);

        try {
            String body = objectMapper.writeValueAsString(resp);
            JsonNode root = objectMapper.readTree(body);

            JsonNode cred = root.path("credentials");
            String tmpSecretId = cred.path("tmpSecretId").asText(null);
            String tmpSecretKey = cred.path("tmpSecretKey").asText(null);
            String token = cred.path("sessionToken").asText(null);

            long expiredTime = root.path("expiredTime").asLong(0L);
            LocalDateTime expireAt = LocalDateTime.ofInstant(java.time.Instant.ofEpochSecond(expiredTime), ZoneId.systemDefault());

            builder.tmpSecretId(tmpSecretId)
                    .tmpSecretKey(tmpSecretKey)
                    .sessionToken(token)
                    .expiredTime(expiredTime)
                    .expireAt(expireAt);

        } catch (Exception e) {
            // 解析失败直接抛出异常，避免返回不完整的凭证
            throw new ExternalServiceException(ResultCode.EXTERNAL_SERVICE_ERROR, "cos",
                    "failed to parse sts response", e);
        }

        return builder.build();
    }

    /** 根据服务端 COS 配置生成可信访问地址。 */
    public String buildPublicUrl(String objectKey) {
        return cosClient.buildPublicUrl(objectKey);
    }

    /** 删除一个已不再被业务数据引用的 COS 对象。 */
    public void deleteObject(String objectKey) {
        if (objectKey == null || objectKey.isBlank() || objectKey.contains("..")) {
            throw new BusinessException(ResultCode.INVALID_OPERATION, "objectKey 不合法");
        }
        cosClient.deleteObject(objectKey);
    }

    /** 判断 URL 是否属于当前配置的 COS/CDN 域名，用于兼容尚未回填 objectKey 的历史附件。 */
    public boolean isManagedPublicUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        try {
            String actualHost = URI.create(url).getHost();
            return actualHost != null && (actualHost.equalsIgnoreCase(hostOf(cosProperties.getPublicUrlPrefix()))
                    || actualHost.equalsIgnoreCase(hostOf(cosProperties.getCustomDomain())));
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private String hostOf(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.contains("://") ? value : "https://" + value;
        String host = URI.create(normalized).getHost();
        return host == null ? "" : host;
    }
}
