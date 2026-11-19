package cn.jualn.miniapp.module.comment.service;

import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.module.comment.entity.Comment;
import cn.jualn.miniapp.module.comment.mapper.CommunityCommentMapper;
import cn.jualn.miniapp.module.post.bo.MentorQaBO.*;
import cn.jualn.miniapp.module.post.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;
import java.time.OffsetDateTime;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MentorAnswersTest {
    final MentorQualification qualification=mock(MentorQualification.class);
    final MentorQuestions questions=mock(MentorQuestions.class);
    final CommunityCommentMapper comments=mock(CommunityCommentMapper.class);
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    final MentorAnswers answers=new MentorAnswers(qualification,questions,comments,json);
    final Proof proof=new Proof(7L,"person","binding","qualification","2",OffsetDateTime.parse("2026-01-01T00:00:00Z"),new Profile("Original","Position","Unit"));
    Comment comment(String old,String newer) {
        var c=Comment.builder().id(1L).targetId(2L).userId(7L).mentorEvidence(old).mentorAttribution(newer).questionVersion("question").firstPublishedAt(java.time.LocalDateTime.of(2026,1,1,8,1)).build();
        when(comments.get(1)).thenReturn(c);return c;
    }
    @Test void completeOldAndNewOrCorroboratedProofHaveIdenticalHistoricalIdentity() throws Exception {
        String full=json.writeValueAsString(proof),minimal=json.writeValueAsString(Map.of("personId","person","bindingId","binding","qualificationId","qualification","qualificationVersion","2"));
        when(qualification.currentForOriginal(7,"person")).thenReturn(new MentorQualification.Decision(MentorQualification.State.INELIGIBLE,null));
        for(String[] pair:new String[][]{{full,null},{null,full},{full,full},{minimal,full}}) {
            comment(pair[0],pair[1]);var attribution=answers.attribution(1);
            assertEquals(proof.profileAtAnswer(),attribution.profileAtAnswer());assertEquals("INACTIVE",attribution.currentCertification().state());assertTrue(answers.publish(1));
        }
        verify(qualification,never()).forWrite(anyLong());
    }
    @Test void conflictingMissingMalformedAndWrongOriginalUserFailClosed() throws Exception {
        String full=json.writeValueAsString(proof);
        for(String[] pair:new String[][]{{"{\"personId\":\"other\"}",full},{"broken",full},{"{}",null},{null,null},{"[]",full}}) {
            comment(pair[0],pair[1]);assertEquals(503,assertThrows(ContractProblemException.class,()->answers.attribution(1)).getStatus().value());
        }
        comment(null,full).setUserId(8L);assertEquals(503,assertThrows(ContractProblemException.class,()->answers.attribution(1)).getStatus().value());
    }
    @Test void newBindingCannotPublishOldCandidateAndNewPersonCannotCertifyHistory() throws Exception {
        var replacement=new Proof(7L,"person","new-binding","qualification","2",proof.qualifiedAt(),new Profile("Current","Position","Unit"));
        var c=comment(null,json.writeValueAsString(proof));c.setFirstPublishedAt(null);
        when(qualification.forWrite(7)).thenReturn(new MentorQualification.Decision(MentorQualification.State.VALID,"current",replacement));
        assertFalse(answers.publish(1));verify(comments).notice(1,"MENTOR_QUALIFICATION_CHANGED");verifyNoInteractions(questions);
        var anotherPerson=new Proof(7L,"other-person","new-binding","qualification","2",proof.qualifiedAt(),replacement.profileAtAnswer());
        when(qualification.currentForOriginal(7,"person")).thenReturn(new MentorQualification.Decision(MentorQualification.State.VALID,"current",anotherPerson));
        var attribution=answers.attribution(1);assertEquals("INACTIVE",attribution.currentCertification().state());assertEquals("Original",attribution.profileAtAnswer().realName());
    }
}
