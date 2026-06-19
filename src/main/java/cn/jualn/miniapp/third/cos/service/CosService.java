package cn.jualn.miniapp.third.cos.service;

import cn.jualn.miniapp.third.cos.client.CosClient;
import cn.jualn.miniapp.third.cos.config.CosProperties;
import cn.jualn.miniapp.third.cos.dto.CosUploadCredentialDTO;
import com.tencent.cloud.Response;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

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
            throw new RuntimeException("failed to parse sts response", e);
        }

        return builder.build();
    }
}
