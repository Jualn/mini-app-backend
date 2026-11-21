package cn.jualn.miniapp.module.person.bo;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

public final class MentorWorkflowBO {
    private MentorWorkflowBO() {}
    public record Candidate(String personId, String realName, String distinguishingLabel, boolean hasMentorProfile) {}
    public record Create(String realName, String position, String affiliation, String reason, String verificationReference) {}
    public record AccountOption(String userId, String nickname, boolean available) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BindingView(String personId, String userId, String nickname, String bindingId, String version, Instant evaluatedAt) {}
    public record BindingChange(String action, String userId, String reason, String verificationReference) {}
}
