package cn.jualn.miniapp.module.person.bo;


public final class MentorWorkflowBO {
    private MentorWorkflowBO() {}
    public record Candidate(String personId, String realName, String distinguishingLabel, boolean hasMentorProfile) {}
    public record Create(String realName, String position, String affiliation, String reason, String verificationReference) {}
}
