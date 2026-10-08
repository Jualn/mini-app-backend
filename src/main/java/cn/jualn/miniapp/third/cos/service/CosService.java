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
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;

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

    public void copyObject(String sourceKey, String destinationKey) {
        cosClient.copyObject(sourceKey, destinationKey);
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
                    || actualHost.equalsIgnoreCase(hostOf(cosProperties.getCustomDomain()))
                    || actualHost.equalsIgnoreCase(hostOf(bucketPublicPrefix())));
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    /** Returns null only for an external URL; a malformed URL on a managed host is rejected. */
    public String resolveManagedObjectKey(String url) {
        if (!isManagedPublicUrl(url)) return null;
        try {
            URI actual = URI.create(url);
            if (!List.of("http", "https").contains(actual.getScheme()) || actual.getUserInfo() != null
                    || actual.getRawQuery() != null || actual.getRawFragment() != null
                    || (actual.getPort() != -1 && actual.getPort() != ("https".equals(actual.getScheme()) ? 443 : 80))) {
                throw new IllegalArgumentException();
            }
            for (String prefix : publicPrefixes()) {
                URI base = URI.create(prefix);
                if (!actual.getHost().equalsIgnoreCase(base.getHost())) continue;
                String root = base.getRawPath() == null ? "" : base.getRawPath();
                root = root.replaceAll("/+$", "") + "/";
                String path = actual.getRawPath();
                if (path == null || !path.startsWith(root)) continue;
                String rawKey = path.substring(root.length());
                // Decode each segment exactly once. Never reinterpret encoded path separators or traversal.
                List<String> segments = new ArrayList<>();
                for (String segment : rawKey.split("/", -1)) {
                    String decoded = URLDecoder.decode(segment.replace("+", "%2B"), StandardCharsets.UTF_8);
                    if (decoded.isBlank() || decoded.contains("..") || decoded.contains("/")
                            || decoded.contains("\\") || decoded.contains("%")
                            || decoded.codePoints().anyMatch(Character::isISOControl)) throw new IllegalArgumentException();
                    segments.add(decoded);
                }
                String key = String.join("/", segments);
                if (key.length() > 512) throw new IllegalArgumentException();
                return key;
            }
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ResultCode.INVALID_OPERATION, "受管附件地址不合法");
        }
        throw new BusinessException(ResultCode.INVALID_OPERATION, "受管附件地址不属于配置的对象路径");
    }

    /** Exact aliases used only to protect legacy URL-only registrations from cleanup. */
    public List<String> managedPublicUrls(String objectKey) {
        String encoded = Arrays.stream(objectKey.split("/", -1))
                .map(part -> URLEncoder.encode(part, StandardCharsets.UTF_8).replace("+", "%20"))
                .collect(java.util.stream.Collectors.joining("/"));
        List<String> urls = new ArrayList<>();
        for (String prefix : publicPrefixes()) {
            String root = prefix.replaceAll("/+$", "");
            urls.add(root + "/" + objectKey);
            urls.add(root + "/" + encoded);
            URI base = URI.create(root);
            String alternate = ("https".equals(base.getScheme()) ? "http" : "https") + root.substring(root.indexOf(':'));
            urls.add(alternate + "/" + objectKey);
            urls.add(alternate + "/" + encoded);
        }
        return urls.stream().distinct().toList();
    }

    private List<String> publicPrefixes() {
        return java.util.stream.Stream.of(cosProperties.getPublicUrlPrefix(), cosProperties.getCustomDomain(), bucketPublicPrefix())
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.contains("://") ? value : "https://" + value).distinct().toList();
    }

    private String bucketPublicPrefix() {
        return "https://" + cosProperties.getBucket() + ".cos." + cosProperties.getRegion() + ".myqcloud.com";
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
