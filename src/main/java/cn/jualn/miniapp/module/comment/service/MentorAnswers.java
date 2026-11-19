package cn.jualn.miniapp.module.comment.service;
import cn.jualn.miniapp.module.post.service.*;
import cn.jualn.miniapp.module.post.bo.MentorQaBO.*;
import cn.jualn.miniapp.module.comment.mapper.CommunityCommentMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.Objects;

@Service @RequiredArgsConstructor
public class MentorAnswers {
    private final MentorQualification qualifications;
    private final MentorQuestions questions;
    private final CommunityCommentMapper comments;
    private final ObjectMapper json;
    public void guard(long actor) {qualifications.guardWrite(actor);}
    public String accept(long actor,long post,String version) {
        questions.accepting(post,version);
        var decision=qualifications.forWrite(actor);
        if(!decision.valid())throw failure(decision.state());
        if(!valid(decision.proof()) || !Objects.equals(decision.proof().userId(),actor))throw failure(MentorQualification.State.UNAVAILABLE);
        return encode(decision.proof());
    }
    /** Acquire Person/authorization locks before the Post -> root -> comment locks. */
    public void guardPublication(long id) {guardPublication(id,null);}
    public void guardPublication(long id,Long reviewer) {var c=comments.get(id);if(c!=null && hasProof(c) && c.getFirstPublishedAt()==null)qualifications.guardPublication(c.getUserId(),reviewer);}
    public boolean publish(long id) {
        var c=comments.get(id);if(c==null || !hasProof(c))return false;
        var proof=original(c);
        if(c.getFirstPublishedAt()!=null)return true;
        var current=qualifications.forWrite(c.getUserId());
        if(current.state()==MentorQualification.State.UNAVAILABLE)throw failure(current.state());
        if(!current.valid() || current.proof()==null || !same(proof,current.proof())) {comments.notice(id,"MENTOR_QUALIFICATION_CHANGED");return false;}
        boolean currentQuestion=questions.firstPublication(c.getTargetId(),c.getQuestionVersion());
        if(!currentQuestion)comments.notice(id,"QUESTION_CHANGED");
        return currentQuestion;
    }
    public Attribution attribution(long id) {
        var c=comments.get(id);if(c==null || !hasProof(c))throw failure(MentorQualification.State.UNAVAILABLE);
        var proof=original(c);String state;
        try {var current=qualifications.currentForOriginal(proof.userId(),proof.personId());state=current==null || current.state()==MentorQualification.State.UNAVAILABLE?"UNAVAILABLE":current.valid() && current.proof()!=null && Objects.equals(current.proof().personId(),proof.personId())?"VALID":"INACTIVE";}
        catch(org.springframework.dao.DataAccessException | cn.jualn.miniapp.common.exception.ContractProblemException unavailable){state="UNAVAILABLE";}
        return new Attribution(c.getQuestionVersion(),proof.qualifiedAt(),PostCommunityService.offset(c.getFirstPublishedAt()),proof.profileAtAnswer(),new Certification(state,PostCommunityService.offset(PostCommunityService.now())));
    }
    public void invalidateChanged(long id) {var c=comments.get(id);if(c!=null && c.getFirstPublishedAt()==null && c.getQuestionVersion()!=null && !questions.matchesVersion(c.getTargetId(),c.getQuestionVersion()))comments.notice(id,"QUESTION_CHANGED");}
    public boolean restorable(long id) {var c=comments.get(id);if(c!=null && hasProof(c))original(c);return c!=null && hasProof(c) && c.getFirstPublishedAt()!=null && questions.summary(c.getTargetId())!=null && Objects.equals(c.getQuestionVersion(),questions.summary(c.getTargetId()).questionVersion());}
    private static boolean hasProof(cn.jualn.miniapp.module.comment.entity.Comment c) {
        return c.getMentorAttribution()!=null || c.getMentorEvidence()!=null && !c.getMentorEvidence().isBlank();
    }
    /** Never fill a historical gap from today's binding or profile. Old minimal evidence can
     * corroborate the immutable proof; only a complete old envelope can stand alone. */
    private Proof original(cn.jualn.miniapp.module.comment.entity.Comment c) {
        try {
            var newer=c.getMentorAttribution()==null?null:json.readTree(c.getMentorAttribution());
            var older=c.getMentorEvidence()==null || c.getMentorEvidence().isBlank()?null:json.readTree(c.getMentorEvidence());
            if(newer!=null && !newer.isObject() || older!=null && !older.isObject()) throw failure(MentorQualification.State.UNAVAILABLE);
            if(newer!=null && older!=null) for(String path:java.util.List.of("/userId","/personId","/bindingId","/qualificationId","/qualificationVersion","/qualifiedAt","/profileAtAnswer/realName","/profileAtAnswer/position","/profileAtAnswer/affiliation")) {
                var old=older.at(path);if(!old.isMissingNode() && !old.asText().equals(newer.at(path).asText())) throw failure(MentorQualification.State.UNAVAILABLE);
            }
            var proof=json.treeToValue(newer==null?older:newer,Proof.class);
            if(!valid(proof) || !Objects.equals(proof.userId(),c.getUserId())) throw failure(MentorQualification.State.UNAVAILABLE);
            return proof;
        } catch(com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException invalid) {throw failure(MentorQualification.State.UNAVAILABLE);}
    }
    private boolean same(Proof a,Proof b) {return Objects.equals(a.userId(),b.userId()) && Objects.equals(a.personId(),b.personId()) && Objects.equals(a.bindingId(),b.bindingId()) && Objects.equals(a.qualificationId(),b.qualificationId());}
    private String encode(Proof p) {try{return json.writeValueAsString(p);}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException(e);}}
    
    private static boolean valid(Proof p) {return p!=null && p.userId()!=null && p.personId()!=null && !p.personId().isBlank() && p.bindingId()!=null && !p.bindingId().isBlank() && p.qualificationId()!=null && !p.qualificationId().isBlank() && p.qualificationVersion()!=null && !p.qualificationVersion().isBlank() && p.qualifiedAt()!=null && p.profileAtAnswer()!=null && p.profileAtAnswer().realName()!=null && !p.profileAtAnswer().realName().isBlank() && p.profileAtAnswer().position()!=null && !p.profileAtAnswer().position().isBlank() && p.profileAtAnswer().affiliation()!=null && !p.profileAtAnswer().affiliation().isBlank();}
    private static cn.jualn.miniapp.common.exception.ContractProblemException failure(MentorQualification.State state) {return cn.jualn.miniapp.module.post.PostProblems.problem(state==MentorQualification.State.UNAVAILABLE?org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE:org.springframework.http.HttpStatus.FORBIDDEN,state==MentorQualification.State.UNAVAILABLE?"mentor-qualification-unavailable":"mentor-answer-forbidden","Trusted original mentor qualification is required");}
}
