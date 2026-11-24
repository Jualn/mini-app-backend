package cn.jualn.miniapp.module.audit.bo;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
/** Protocol projection; no credentials or execution payloads are exposed. */
public final class SafetyReviewProjection {
    private SafetyReviewProjection() {}
    public record BusinessTarget(String type,@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String organizationId) {public BusinessTarget(String type){this(type,null);}}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Round(String submissionVersion,String scene,String namespace,BusinessTarget businessTarget,
        String submissionKind,String state,String previousTaskId,String supersededByTaskId,String decisionOrigin,String invalidationReason) {}
    public record Evidence(String submissionVersion,boolean complete,List<Check> checks) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Check(String checkId,String inputReference,boolean required,String executionState,String result,
        String provider,String traceId,Facts providerFacts,String supersedesCheckId) {}
    public record Facts(String resultCode,String suggestion,List<String> matchedLabels,String detail) {}
}
