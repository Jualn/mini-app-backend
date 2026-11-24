package cn.jualn.miniapp.module.post.bo;
import java.time.OffsetDateTime;
import com.fasterxml.jackson.annotation.JsonInclude;
public final class MentorQaBO {
    private MentorQaBO() { }
    public record Profile(String realName,String position,String affiliation) { }
    public record Proof(Long userId,String personId,String bindingId,String qualificationId,String qualificationVersion,
            OffsetDateTime qualifiedAt,Profile profileAtAnswer) { }
    public record Certification(String state,OffsetDateTime evaluatedAt) { }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Attribution(String questionVersion,OffsetDateTime qualifiedAt,OffsetDateTime firstPublishedAt,
            Profile profileAtAnswer,Certification currentCertification) { }
    public record Summary(String questionVersion,String answerState,int publicAnswerCount,boolean contentLocked,OffsetDateTime evaluatedAt) { }
}
