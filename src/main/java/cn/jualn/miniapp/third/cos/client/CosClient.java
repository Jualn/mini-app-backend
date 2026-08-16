package cn.jualn.miniapp.third.cos.client;

import cn.jualn.miniapp.common.exception.ExternalServiceException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.third.cos.config.CosProperties;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.http.HttpMethodName;
import com.qcloud.cos.model.GeneratePresignedUrlRequest;
import com.tencent.cloud.CosStsClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.net.URL;
import java.util.Date;

import com.tencent.cloud.Response;

import java.util.TreeMap;

/**
 * COS SDK 轻量封装。
 *
 * <p>仅封装与本项目相关的最小能力：生成 PUT 预签名 URL 与拼接公网访问地址。</p>
 */
@Component
@RequiredArgsConstructor
public class CosClient {

    private final COSClient cosSdkClient;
    private final CosProperties cosProperties;

    /**
     * 获取 COS STS 临时密钥（最小实现）。
     *
     * <p>返回值为 CosStsClient.getCredential 的原始 Response，前端可直接使用其中的 credentials、expiredTime 等字段。</p>
     */
    public Response getCredential(String prefix) {
        TreeMap<String, Object> config = new TreeMap<>();
        // 必要参数：云 API 密钥
        config.put("secretId", cosProperties.getSecretId());
        config.put("secretKey", cosProperties.getSecretKey());

        // 临时密钥有效期（秒）——复用 presignExpireSeconds 作为默认值，最小 300
        int duration = Math.toIntExact(Math.max(300L, cosProperties.getPresignExpireSeconds()));
        config.put("durationSeconds", duration);

        // 作用域：所在 bucket 与 region
        config.put("bucket", cosProperties.getBucket());
        config.put("region", cosProperties.getRegion());

        // 允许的前缀，限定为本次上传对象 {targetType}/{userId}/*
        config.put("allowPrefix", prefix + "*");

        // 允许的操作，仅开放上传相关权限
        config.put("allowActions", new String[]{"cos:PutObject", "cos:PostObject"});

        try {
            // 调用腾讯云提供的工具方法生成临时密钥
            return CosStsClient.getCredential(config);
        } catch (Exception e) {
            throw new ExternalServiceException(ResultCode.EXTERNAL_SERVICE_ERROR, "cos",
                    "failed to get cos sts credential", e);
        }
    }
    // test-only helpers (parsing/canonical request) have been moved to test sources

    /**
     * 生成对象 PUT 预签名 URL。
     *
     * @param objectKey  COS 对象键
     * @param expiration 过期时间
     * @return 预签名 URL
     */
    public URL generatePresignedPutUrl(String objectKey, Date expiration) {
        GeneratePresignedUrlRequest request =
                new GeneratePresignedUrlRequest(cosProperties.getBucket(), objectKey, HttpMethodName.PUT);
        request.setExpiration(expiration);
        return cosSdkClient.generatePresignedUrl(request);
    }

    /**
     * 构建对象对外访问地址。
     *
     * @param objectKey COS 对象键
     * @return 文件公网 URL
     */
    public String buildPublicUrl(String objectKey) {
        String prefix = cosProperties.getPublicUrlPrefix();
        if (prefix.endsWith("/")) {
            return prefix + objectKey;
        }
        return prefix + "/" + objectKey;
    }
}
