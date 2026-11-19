package cn.jualn.miniapp.third.cos.config;

import com.qcloud.cos.COSClient;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Storage namespace routing only; subject permissions belong to the media caller. */
@Component
@RequiredArgsConstructor
public class CosStorageBuckets {
    public static final String PRIVATE_PREFIX = "private/";
    private final CosProperties properties;
    private final COSClient sdk;
    @Value("${cos.private-bucket:}") private String privateBucket;

    public String forKey(String key) {
        if (key == null || key.isBlank() || key.contains("..")) throw new IllegalArgumentException("Invalid storage key");
        if (!key.startsWith(PRIVATE_PREFIX)) return properties.getBucket();
        if (privateBucket == null || !privateBucket.matches("[a-z0-9-]+-[0-9]+") || privateBucket.equals(properties.getBucket())) throw unavailable();
        return privateBucket;
    }
    /** No permissive fallback when the configured private bucket has anonymous ACL grants. */
    public void requirePrivate(String key) {
        String bucket = forKey(key);
        if (!key.startsWith(PRIVATE_PREFIX)) return;
        try {
            var acl = sdk.getBucketAcl(bucket);
            if (acl == null || acl.getOwner() == null || acl.getOwner().getId() == null || acl.getGrantsAsList().stream()
                    .anyMatch(g -> !acl.getOwner().getId().equals(g.getGrantee().getIdentifier()))) throw unavailable();
            com.qcloud.cos.model.BucketPolicy policy;
            try {policy=sdk.getBucketPolicy(bucket);}
            catch(com.qcloud.cos.exception.CosServiceException missingPolicy){if(missingPolicy.getStatusCode()==404)return;throw missingPolicy;}
            if(policy==null || policy.getPolicyText()==null || policy.getPolicyText().isBlank())throw unavailable();
            {
                var document=new com.fasterxml.jackson.databind.ObjectMapper().readTree(policy.getPolicyText());
                var statements=document.get("statement");
                if(statements==null || !statements.isArray())throw unavailable();
                for(var statement:statements) {
                    if(!"allow".equalsIgnoreCase(statement.path("effect").asText()))continue;
                    var principal=statement.get("principal");
                    if(principal==null || principal.toString().contains("*") || principal.toString().toLowerCase(java.util.Locale.ROOT).contains("anyone"))throw unavailable();
                }
            }
        } catch (java.io.IOException failure) {throw unavailable();}
        catch (cn.jualn.miniapp.common.exception.ContractProblemException problem) {throw problem;}
        catch (RuntimeException failure) {throw unavailable();}
    }
    private static cn.jualn.miniapp.common.exception.ContractProblemException unavailable() {
        return new cn.jualn.miniapp.common.exception.ContractProblemException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"/problems/service-unavailable","Private media storage unavailable");
    }
}
