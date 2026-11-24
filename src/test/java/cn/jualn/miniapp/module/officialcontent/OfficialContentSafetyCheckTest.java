package cn.jualn.miniapp.module.officialcontent;

import cn.jualn.miniapp.module.audit.enums.AuditScene;
import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.module.audit.service.*;
import cn.jualn.miniapp.module.audit.mapper.ContentAuditLogMapper;
import cn.jualn.miniapp.module.audit.entity.ContentAuditLog;
import cn.jualn.miniapp.module.audit.bo.AuditReserveResultBO;
import cn.jualn.miniapp.module.audit.bo.AuditReserveBO;
import cn.jualn.miniapp.module.media.bo.MediaAttachmentBO;
import cn.jualn.miniapp.module.user.service.UserWechatIdentityService;
import cn.jualn.miniapp.third.wx.client.WxClient;
import cn.jualn.miniapp.third.wx.dto.WxMsgSecCheckResponse;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Provider protocol doubles only: no claim of real WeChat safety or COS evidence. */
class OfficialContentSafetyCheckTest {
    final WxClient wx=mock(WxClient.class);final UserWechatIdentityService users=mock(UserWechatIdentityService.class);
    final AuditReservationService reservations=mock(AuditReservationService.class);final ContentAuditLogMapper logs=mock(ContentAuditLogMapper.class);
    final OfficialContentSafetyCheckService checks=new OfficialContentSafetyCheckService(wx,users,reservations,logs);
    @Test void unknownIdentityAndIncompleteTextDecisionCannotPublish() {
        assertEquals(503,assertThrows(ContractProblemException.class,()->checks.text(9,"saved title and body")).getStatus().value());verifyNoInteractions(wx);
        when(users.getMiniOpenid(9L)).thenReturn("synthetic");var r=new WxMsgSecCheckResponse();r.setErrcode(0);var result=new WxMsgSecCheckResponse.ResultInfo();result.setSuggest("review");r.setResult(result);when(wx.msgSecCheck(any())).thenReturn(r);
        assertEquals(503,assertThrows(ContractProblemException.class,()->checks.text(9,"saved body")).getStatus().value());result.setSuggest("reject");assertEquals(409,assertThrows(ContractProblemException.class,()->checks.text(9,"saved body")).getStatus().value());
    }
    @Test void onlyCompleteCandidateImageEvidenceInItsOwnSceneIsAccepted() {
        var image=MediaAttachmentBO.builder().id(4L).kind("IMAGE").verified(true).objectKey("official-resource/image").url("https://synthetic.invalid/image").build();
        when(reservations.reserveAuditLogs(any())).thenReturn(AuditReserveResultBO.builder().mediaItems(List.of(AuditReserveResultBO.MediaItem.builder().auditLogId(7L).build())).build());
        when(logs.selectById(7L)).thenReturn(ContentAuditLog.builder().targetType(11).targetId(1L).finalResult(1).build());
        assertDoesNotThrow(()->checks.media(9,1,List.of(image)));
        var capture=org.mockito.ArgumentCaptor.forClass(AuditReserveBO.class);verify(reservations).reserveAuditLogs(capture.capture());assertEquals(AuditScene.OFFICIAL_CONTENT_CANDIDATE,capture.getValue().getAuditScene());assertEquals(9L,capture.getValue().getAuthenticatedActorAccountId());
        when(logs.selectById(7L)).thenReturn(ContentAuditLog.builder().targetType(9).targetId(1L).finalResult(1).build());
        assertEquals(503,assertThrows(ContractProblemException.class,()->checks.media(9,1,List.of(image))).getStatus().value());
    }
    @Test void externalVideoAndLateCandidateCallbacksDoNotCreateAPublication() {
        var link=MediaAttachmentBO.builder().kind("LINK").url("https://synthetic.invalid/video").build();
        assertEquals(503,assertThrows(ContractProblemException.class,()->checks.media(9,1,List.of(link))).getStatus().value());verifyNoInteractions(reservations,wx);
        var callback=new OfficialContentCandidateAuditCallback();callback.onPass(1L,7L);callback.onReject(1L,7L,"late");
    }
}
