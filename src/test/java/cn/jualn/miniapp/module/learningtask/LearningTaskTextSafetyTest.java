package cn.jualn.miniapp.module.learningtask;

import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.module.audit.service.LearningTaskTextSafetyCheckService;
import cn.jualn.miniapp.module.user.service.UserWechatIdentityService;
import cn.jualn.miniapp.third.wx.client.WxClient;
import cn.jualn.miniapp.third.wx.dto.WxMsgSecCheckResponse;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LearningTaskTextSafetyTest {
    final WxClient wx=mock(WxClient.class);
    final UserWechatIdentityService users=mock(UserWechatIdentityService.class);
    final LearningTaskTextSafetyCheckService safety=new LearningTaskTextSafetyCheckService(wx,users);
    WxMsgSecCheckResponse response(String suggest) {
        var response=new WxMsgSecCheckResponse(); response.setErrcode(0);
        var result=new WxMsgSecCheckResponse.ResultInfo(); result.setSuggest(suggest); response.setResult(result);
        response.setVerifiedPassStructure("pass".equals(suggest)); return response;
    }
    @Test void trustedOpenidAndUnicodeChunksAreUsed() {
        when(users.getMiniOpenid(9L)).thenReturn("trusted");
        when(wx.msgSecCheck(any())).thenAnswer(invocation -> {
            var request=(cn.jualn.miniapp.third.wx.dto.WxMsgSecCheckRequest)invocation.getArgument(0);
            assertEquals("trusted",request.getOpenid()); assertEquals(1,request.getScene());
            org.junit.jupiter.api.Assertions.assertTrue(request.getContent().codePointCount(0,request.getContent().length())<=2000);
            return response("pass");
        });
        safety.check(9,"😀".repeat(3000));
        org.mockito.Mockito.verify(wx,org.mockito.Mockito.times(2)).msgSecCheck(any());
    }
    @Test void riskyIsCanonicalValidationAndUnknownIsDependency() {
        when(users.getMiniOpenid(9L)).thenReturn("trusted"); when(wx.msgSecCheck(any())).thenReturn(response("risky"));
        assertEquals(400,assertThrows(ContractProblemException.class,()->safety.check(9,"text")).getStatus().value());
        when(wx.msgSecCheck(any())).thenReturn(response("review"));
        assertEquals("CONTENT_SAFETY",assertThrows(ContractProblemException.class,()->safety.check(9,"text")).getMessage());
    }
    @Test void missingOpenidProviderFailureAndConflictingDetailNeverPass() {
        assertEquals(503,assertThrows(ContractProblemException.class,()->safety.check(9,"text")).getStatus().value());
        when(users.getMiniOpenid(9L)).thenReturn("trusted"); var response=response("pass"); response.setErrcode(40001);
        when(wx.msgSecCheck(any())).thenReturn(response);
        assertEquals(503,assertThrows(ContractProblemException.class,()->safety.check(9,"text")).getStatus().value());
        response.setErrcode(0); var detail=new WxMsgSecCheckResponse.DetailInfo(); detail.setErrcode(0); detail.setSuggest("risky");
        response.setVerifiedPassStructure(false);
        response.setDetail(java.util.List.of(detail));
        assertEquals(503,assertThrows(ContractProblemException.class,()->safety.check(9,"text")).getStatus().value());
    }
}
