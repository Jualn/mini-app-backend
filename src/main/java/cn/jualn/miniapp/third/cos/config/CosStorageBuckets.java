package cn.jualn.miniapp.third.cos.config;

import cn.jualn.miniapp.common.exception.ContractProblemException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.exception.CosServiceException;
import com.qcloud.cos.model.BucketPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Storage namespace routing only; subject permissions belong to the media caller.
 */
@Component
@RequiredArgsConstructor
public class CosStorageBuckets {
    public static boolean isPrivateKey(String key) {
        return key != null && (key.startsWith("post/") || key.startsWith("comment/")
                || key.startsWith("growth-record/") || key.startsWith("points/")
                || key.startsWith("learning-task/"));
    }
    private final CosProperties properties;
    private final COSClient sdk;
    private final ObjectMapper json;
    @Value("${cos.private-bucket:}")
    private String privateBucket;

    public String forKey(String key) {
        if (key == null || key.isBlank() || key.contains(".."))
            throw new IllegalArgumentException("Invalid storage key");
        if (!isPrivateKey(key)) return properties.getBucket();
        if (privateBucket == null || !privateBucket.matches("[a-z0-9-]+-[0-9]+") || privateBucket.equals(properties.getBucket()))
            throw unavailable();
        return privateBucket;
    }

    /**
     * No permissive fallback when the configured private bucket has anonymous ACL grants.
     */
    public void requirePrivate(String key) {
        String bucket = forKey(key);
        if (!isPrivateKey(key)) return;
        try {
            var acl = sdk.getBucketAcl(bucket);
            if (acl == null || acl.getOwner() == null || acl.getOwner().getId() == null || acl.getGrantsAsList().stream()
                    .anyMatch(g -> !acl.getOwner().getId().equals(g.getGrantee().getIdentifier()))) throw unavailable();
            BucketPolicy policy;
            try {
                policy = sdk.getBucketPolicy(bucket);
            } catch (CosServiceException missingPolicy) {
                if (missingPolicy.getStatusCode() == 404) return;
                throw missingPolicy;
            }
            if (policy == null || policy.getPolicyText() == null || policy.getPolicyText().isBlank())
                throw unavailable();
            {
                var document = json.readTree(policy.getPolicyText());
                var statements = document.get("statement");
                if (statements == null || !statements.isArray()) throw unavailable();
                for (var statement : statements) {
                    if (!"allow".equalsIgnoreCase(statement.path("effect").asText())) continue;
                    var principal = statement.get("principal");
                    if (principal == null || principal.toString().contains("*") || principal.toString().toLowerCase(java.util.Locale.ROOT).contains("anyone"))
                        throw unavailable();
                }
            }
        } catch (ContractProblemException problem) {
            throw problem;
        } catch (IOException | RuntimeException failure) {
            throw unavailable();
        }
    }

    private static ContractProblemException unavailable() {
        return new ContractProblemException(HttpStatus.SERVICE_UNAVAILABLE, "/problems/service-unavailable", "Private media storage unavailable");
    }
}
