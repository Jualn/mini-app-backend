package cn.jualn.miniapp.module.post;

import cn.jualn.miniapp.module.post.service.MentorQualification;
import cn.jualn.miniapp.module.post.service.PostCommentPermissions;
import cn.jualn.miniapp.module.post.service.PostCursorCodec;
import cn.jualn.miniapp.module.post.service.PostRepresentationTags;
import cn.jualn.miniapp.module.post.service.UnavailableMentorQualification;
import cn.jualn.miniapp.module.post.bo.PostEditableBO;
import cn.jualn.miniapp.module.post.bo.PostWritePermission;
import cn.jualn.miniapp.module.audit.service.AuditReservationService;
import cn.jualn.miniapp.module.audit.service.PostCandidateAuditCallback;
import cn.jualn.miniapp.module.audit.service.PostSafetyCheckService;
import cn.jualn.miniapp.module.audit.mapper.ContentAuditLogMapper;
import cn.jualn.miniapp.module.audit.entity.ContentAuditLog;
import cn.jualn.miniapp.module.audit.bo.AuditReserveResultBO;
import cn.jualn.miniapp.module.media.bo.AttachmentItemBO;
import cn.jualn.miniapp.module.user.service.UserWechatIdentityService;
import cn.jualn.miniapp.third.wx.client.WxClient;
import cn.jualn.miniapp.third.wx.dto.WxMsgSecCheckResponse;
import cn.jualn.miniapp.module.audit.enums.AuditScene;
import cn.jualn.miniapp.module.media.enums.MediaType;
import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.module.post.dto.request.PostEditRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import java.util.List;
import java.util.Set;

class PostOrganizationPolicyTest {
    @Test void noSourceCannotGrantMentorRightsAndMalformedPositiveDecisionIsUnavailable() {
        var permissions=new PostCommentPermissions(new UnavailableMentorQualification());
        assertEquals(PostWritePermission.blocked(),permissions.answer(7L,"MENTOR_QA"));
        assertEquals(503,assertThrows(ContractProblemException.class,()->permissions.requireMentor(7)).getStatus().value());
        var broken=new PostCommentPermissions(id->new MentorQualification.Decision(MentorQualification.State.VALID,null));
        assertEquals(PostWritePermission.blocked(),broken.answer(7L,"MENTOR_QA"));
        assertEquals(PostWritePermission.denied("TYPE_UNSUPPORTED"),permissions.answer(7L,"FUTURE"));
        assertEquals(PostWritePermission.allowed(),permissions.answer(7L,"NORMAL"));
    }
    @Test void queryBoundCursorsRejectMalformedAndChangedPredicates() {
        var codec=new PostCursorCodec();String c=codec.encode("query",11,2);
        assertEquals(new PostCursorCodec.Anchor(11,2),codec.decode(c,"query"));
        assertEquals("CURSOR_QUERY_MISMATCH",assertThrows(ContractProblemException.class,()->codec.decode(c,"other")).getErrors().get(0).code());
        for(String value:List.of("","invalid","!","a".repeat(257)))
            { assertEquals("INVALID_CURSOR",assertThrows(ContractProblemException.class,()->codec.decode(value,"query")).getErrors().get(0).code()); }
    }
    @Test void editPresenceAndUnknownFieldsCannotBecomeUnconditionalReplacement() throws Exception {
        var json=new ObjectMapper();var c=json.readValue("{\"title\":null,\"topicIds\":[]}",PostEditRequest.class);
        assertEquals(Set.of("title","topicIds"),c.getFields());assertNull(c.getTitle());
        assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class,()->json.readValue("{\"postType\":\"NORMAL\"}",PostEditRequest.class));
        assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class,()->json.readValue("{\"authorId\":42}",PostEditRequest.class));
    }
    @Test void completeRepresentationAndRevisionChangeStrongTag() {
        var tags=new PostRepresentationTags(new ObjectMapper());
        var one=new PostEditableBO(1L,"NORMAL",null,"text",List.of(),null,List.of(2L,3L));
        var reorder=new PostEditableBO(1L,"NORMAL",null,"text",List.of(),null,List.of(3L,2L));
        assertNotEquals(tags.tag(one,1),tags.tag(reorder,1));assertNotEquals(tags.tag(one,1),tags.tag(one,2));
    }
    @Test void textAndCandidateMediaDecisionsAreStrictAndCannotPublishViaCallback() {
        var wx=mock(WxClient.class);var users=mock(UserWechatIdentityService.class);var reserve=mock(AuditReservationService.class);var logs=mock(ContentAuditLogMapper.class);
        when(users.getMiniOpenid(7L)).thenReturn("trusted-openid");
        var safety=new PostSafetyCheckService(wx,users,reserve,logs);
        var response=new WxMsgSecCheckResponse();response.setErrcode(0);var result=new WxMsgSecCheckResponse.ResultInfo();result.setSuggest("pass");response.setResult(result);
        when(wx.msgSecCheck(any())).thenReturn(response);assertDoesNotThrow(()->safety.check(7,1,"text",List.of()));
        result.setSuggest("risky");assertEquals(422,assertThrows(ContractProblemException.class,()->safety.check(7,1,"text",List.of())).getStatus().value());
        result.setSuggest("unknown");assertEquals(503,assertThrows(ContractProblemException.class,()->safety.check(7,1,"text",List.of())).getStatus().value());
        result.setSuggest("pass");response.setErrcode(1);assertEquals(503,assertThrows(ContractProblemException.class,()->safety.check(7,1,"text",List.of())).getStatus().value());
        when(reserve.reserveAuditLogs(any())).thenReturn(AuditReserveResultBO.builder().mediaItems(List.of(AuditReserveResultBO.MediaItem.builder().auditLogId(5L).build())).build());
        when(logs.selectById(5L)).thenReturn(ContentAuditLog.builder().targetId(1L).targetType(AuditScene.POST_EDIT.getCode()).finalResult(1).build());
        var image=AttachmentItemBO.builder().type(MediaType.IMAGE).url("https://cdn.example/immutable").build();
        assertDoesNotThrow(()->safety.check(7,1,null,List.of(image)));
        when(logs.selectById(5L)).thenReturn(ContentAuditLog.builder().targetId(2L).targetType(AuditScene.POST_EDIT.getCode()).finalResult(1).build());
        assertEquals(503,assertThrows(ContractProblemException.class,()->safety.check(7,1,null,List.of(image))).getStatus().value());
        new PostCandidateAuditCallback().onReject(1L,5L,"late"); // Callback has no state owner or publishing dependency.
    }
}
