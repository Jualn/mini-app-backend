package cn.jualn.miniapp.third.cos.client;

import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.common.exception.ExternalServiceException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.third.cos.config.CosProperties;
import cn.jualn.miniapp.third.cos.config.CosStorageBuckets;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.exception.CosServiceException;
import com.qcloud.cos.model.*;
import com.tencent.cloud.CosStsClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import com.tencent.cloud.Response;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * COS SDK 轻量封装。
 *
 * <p>仅封装与本项目相关的最小能力：生成上传凭证、公网访问地址与删除对象。</p>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class CosClient {

    private final COSClient cosSdkClient;
    private final CosProperties cosProperties;
    private final ObjectProvider<CosStorageBuckets> storage;

    public String bucketFor(String key) {
        if (key.startsWith("private/")) {
            if (storage == null || storage.getIfAvailable() == null)
                throw new IllegalStateException("Private storage configuration required");
            return storage.getObject().forKey(key);
        }
        return cosProperties.getBucket();
    }

    private void privateReady(String key) {
        if (key.startsWith("private/")) storage.getObject().requirePrivate(key);
    }


    /**
     * Prefix-wide credentials cannot meet the upload protocol.
     */
    public Response getCredential(String prefix) {
        throw new IllegalArgumentException("Use exact allocated object keys");
    }

    /**
     * Exact key policy. Header conditions apply only to object-creating operations.
     */
    public String uploadPolicy(List<String> keys) {
        if (keys == null || keys.isEmpty() || keys.stream().anyMatch(k -> k == null || k.isBlank()
                || k.contains("*") || k.contains("?") || k.contains("..")))
            throw new IllegalArgumentException("Invalid exact object keys");
        String bucket = bucketFor(keys.get(0));
        if (keys.stream().anyMatch(k -> !bucket.equals(bucketFor(k))))
            throw new IllegalArgumentException("Mixed storage targets");
        String appid = bucket.substring(bucket.lastIndexOf('-') + 1);
        var resources = keys.stream().map(k -> "qcs::cos:" + cosProperties.getRegion() + ":uid/" + appid + ":" + bucket + "/" + k).toList();
        var statements = new ArrayList<Map<String, Object>>();
        var writes = List.of("name/cos:PutObject", "name/cos:InitiateMultipartUpload", "name/cos:CompleteMultipartUpload");
        statements.add(Map.of("effect", "allow", "action", writes, "resource", resources,
                "condition", Map.of("string_equal", Map.of("cos:x-cos-forbid-overwrite", "true"))));
        statements.add(Map.of("effect", "allow", "action", List.of("name/cos:ListParts", "name/cos:UploadPart", "name/cos:AbortMultipartUpload"), "resource", resources));
        // Never allow callers to grant access while creating an object.
        for (String header : List.of("x-cos-acl", "x-cos-grant-read", "x-cos-grant-write", "x-cos-grant-read-acp", "x-cos-grant-write-acp", "x-cos-grant-full-control"))
            statements.add(Map.of("effect", "deny", "action", writes, "resource", resources,
                    "condition", Map.of("null", Map.of("cos:" + header, "false"))));
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of("version", "2.0", "statement", statements));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public Response getExactCredential(List<String> objectKeys) {
        try {
            // COS ignores forbid-overwrite on a versioned bucket: refuse such a configuration.
            String bucket = bucketFor(objectKeys.get(0));
            privateReady(objectKeys.get(0));
            var versioning = cosSdkClient.getBucketVersioningConfiguration(bucket);
            if (versioning == null || !"Off".equals(versioning.getStatus()))
                throw new IllegalStateException("Public upload bucket must have versioning disabled");
            TreeMap<String, Object> config = new TreeMap<>();
            config.put("secretId", cosProperties.getSecretId());
            config.put("secretKey", cosProperties.getSecretKey());
            config.put("durationSeconds", Math.toIntExact(Math.max(300L, cosProperties.getPresignExpireSeconds())));
            config.put("bucket", bucket);
            config.put("region", cosProperties.getRegion());
            config.put("policy", uploadPolicy(objectKeys));
            return CosStsClient.getCredential(config);
        } catch (Exception e) {
            log.warn("result=failure errorCategory=remote provider=cos exceptionType={}", e.getClass().getSimpleName());
            var problem = new ContractProblemException(HttpStatus.SERVICE_UNAVAILABLE, "/problems/service-unavailable", "Safe upload credentials unavailable");
            problem.initCause(e);
            throw problem;
        }
    }

    /**
     * Downloads a server-managed candidate into a caller-owned temporary file with a hard byte bound.
     */
    public void downloadObject(String objectKey, Path destination, long maxBytes) {
        try (var input = cosSdkClient.getObject(bucketFor(objectKey), objectKey).getObjectContent();
             var output = java.nio.file.Files.newOutputStream(destination)) {
            byte[] buffer = new byte[65536];
            long total = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > maxBytes)
                    throw ContractProblemException.validation(new ContractProblemException.Violation("body", "#/url", "INVALID_VALUE", "Media exceeds inspection byte limit"));
                output.write(buffer, 0, count);
            }
        } catch (ContractProblemException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new ExternalServiceException(ResultCode.EXTERNAL_SERVICE_ERROR, "cos", "failed to read managed media", e);
        }
    }
    // test-only helpers (parsing/canonical request) have been moved to test sources

    /**
     * 构建对象对外访问地址。
     *
     * @param objectKey COS 对象键
     * @return 文件公网 URL
     */
    public String buildPublicUrl(String objectKey) {
        if (objectKey.startsWith("private/")) throw new IllegalArgumentException("Private objects have no public URL");
        String prefix = cosProperties.getPublicUrlPrefix();
        if (prefix.endsWith("/")) {
            return prefix + objectKey;
        }
        return prefix + "/" + objectKey;
    }

    /**
     * 删除指定 COS 对象。
     */
    public void deleteObject(String objectKey) {
        try {
            cosSdkClient.deleteObject(bucketFor(objectKey), objectKey);
        } catch (Exception e) {
            throw new ExternalServiceException(ResultCode.EXTERNAL_SERVICE_ERROR, "cos",
                    "failed to delete cos object", e);
        }
    }

    /**
     * Copy to a new server-only key; never grant client credentials for this prefix.
     */
    public void copyObject(String sourceKey, String destinationKey) {
        try {
            privateReady(destinationKey);
            var request = new CopyObjectRequest(bucketFor(sourceKey), sourceKey, bucketFor(destinationKey), destinationKey);
            if (destinationKey.startsWith("private/"))
                request.setCannedAccessControlList(CannedAccessControlList.Private);
            cosSdkClient.copyObject(request);
        } catch (CosServiceException failure) {
            if (failure.getStatusCode() == 404)
                throw ContractProblemException.conflict("attachment-not-ready", "Upload object is not ready");
            throw new ExternalServiceException(ResultCode.EXTERNAL_SERVICE_ERROR, "cos", "failed to copy managed media", failure);
        } catch (RuntimeException failure) {
            throw new ExternalServiceException(ResultCode.EXTERNAL_SERVICE_ERROR, "cos",
                    "failed to copy profile media", failure);
        }
    }

    /**
     * Caller already authorized the immutable key. No redirects or caller-controlled hosts.
     */
    public COSObject openObject(String key, long start, long end) {
        try {
            var request = new GetObjectRequest(bucketFor(key), key);
            request.setRange(start, end);
            return cosSdkClient.getObject(request);
        } catch (RuntimeException failure) {
            throw new ExternalServiceException(ResultCode.EXTERNAL_SERVICE_ERROR, "cos", "failed to read controlled media", failure);
        }
    }

    /**
     * Provider-only capability, generated immediately before the provider invocation.
     */
    public String providerReadUrl(String key) {
        try {
            var request = new GeneratePresignedUrlRequest(bucketFor(key), key, com.qcloud.cos.http.HttpMethodName.GET);
            request.setExpiration(new java.util.Date(System.currentTimeMillis() + 15 * 60 * 1000L));
            var url = cosSdkClient.generatePresignedUrl(request);
            if (!"https".equals(url.getProtocol())) throw new IllegalStateException("HTTPS storage URL required");
            return url.toString();
        } catch (RuntimeException failure) {
            throw new ExternalServiceException(ResultCode.EXTERNAL_SERVICE_ERROR, "cos", "failed to authorize media safety read", failure);
        }
    }
}
