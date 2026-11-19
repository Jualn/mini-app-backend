package cn.jualn.miniapp.module.post.service;
import cn.jualn.miniapp.module.post.bo.MentorQaBO.Summary;
import cn.jualn.miniapp.module.post.mapper.*;
import cn.jualn.miniapp.common.exception.ContractProblemException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.Objects;

@Service @RequiredArgsConstructor
public class MentorQuestions {
    private final PostMapper posts;
    private final MentorQuestionMapper questions;
    private final PostPublicAccess access;
    public void requireKnownFilter(cn.jualn.miniapp.module.post.bo.PostFeedQueryBO query) {if(questions.unknownFiltered(query)>0)throw unavailable();}
    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public Summary summary(long id) {
        var p=posts.includingDeleted(id);
        if(p==null || !"MENTOR_QA".equals(p.getPostType()))return null;
        if(p.getQuestionVersion()==null || questions.unknownAnswers(id)>0)throw unavailable();
        int retainedAnswers=questions.publicAnswers(id,p.getQuestionVersion());
        if(retainedAnswers>0 && !Boolean.TRUE.equals(p.getQuestionContentLocked()))throw unavailable();
        int count=PostPublicAccess.visible(p,PostCommunityService.now())?retainedAnswers:0;
        return new Summary(p.getQuestionVersion(),count==0?"UNANSWERED":"ANSWERED",count,Boolean.TRUE.equals(p.getQuestionContentLocked()),PostCommunityService.offset(PostCommunityService.now()));
    }
    public boolean matchesVersion(long id,String version) {var p=posts.lockPost(id);return p!=null && Objects.equals(version,p.getQuestionVersion());}
    @Transactional(propagation=Propagation.MANDATORY)
    public String accepting(long id,String version) {
        access.require(id,true);var p=posts.lockPost(id);
        if(p.getQuestionVersion()==null)throw unavailable();
        if(version==null || version.isBlank())throw cn.jualn.miniapp.module.post.PostProblems.invalid("questionVersion","REQUIRED","Read the current question version");
        if(!Objects.equals(version,p.getQuestionVersion()))throw ContractProblemException.conflict("mentor-question-version-stale","Question content changed");
        return p.getQuestionVersion();
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public boolean firstPublication(long id,String version) {
        access.require(id,true);var p=posts.lockPost(id);
        if(!Objects.equals(version,p.getQuestionVersion()))return false;
        if(questions.freeze(id,version)!=1)throw new IllegalStateException("Question publication changed");
        return true;
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void edit(long id,boolean contentChanged) {
        var p=posts.lockPost(id);if(!"MENTOR_QA".equals(p.getPostType()))return;
        if(p.getQuestionVersion()==null)throw unavailable();
        if(contentChanged && Boolean.TRUE.equals(p.getQuestionContentLocked()))throw ContractProblemException.conflict("mentor-question-content-locked","The question has a published formal answer");
        if(contentChanged)questions.version(id,java.util.UUID.randomUUID().toString());
    }
    private static ContractProblemException unavailable() {return cn.jualn.miniapp.module.post.PostProblems.problem(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"mentor-qualification-unavailable","Historical question or answer evidence cannot be established");}
}
