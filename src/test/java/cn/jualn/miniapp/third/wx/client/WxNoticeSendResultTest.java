package cn.jualn.miniapp.third.wx.client;

import cn.jualn.miniapp.common.exception.ExternalServiceException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.third.wx.dto.MiniSubscribeMessageResponse;
import cn.jualn.miniapp.third.wx.dto.MpSubscribeMessageResponse;
import cn.jualn.miniapp.third.wx.notice.WxNoticeSendResult;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WxNoticeSendResultTest {
    @Test void incompleteResultsRemainUnknownAndMiniDoesNotInventMessageIds() {
        assertEquals(WxNoticeSendResult.Outcome.UNKNOWN, WxNoticeSendResult.official(new MpSubscribeMessageResponse()).outcome());
        var mini = new MiniSubscribeMessageResponse(); mini.setErrcode(0);
        assertEquals(WxNoticeSendResult.Outcome.ACCEPTED, WxNoticeSendResult.mini(mini).outcome());
        assertNull(WxNoticeSendResult.mini(mini).messageId());
    }
    @Test void explicitRejectionsAndTransportUncertaintyHaveDifferentRecoverySemantics() {
        var denied = new MiniSubscribeMessageResponse(); denied.setErrcode(43101); denied.setErrmsg("private-provider-detail");
        var result = WxNoticeSendResult.mini(denied);
        assertEquals(WxNoticeSendResult.Outcome.REJECTED_PERMANENT, result.outcome());
        assertFalse(result.toString().contains("private-provider-detail"));
        assertEquals(WxNoticeSendResult.Outcome.REJECTED_RETRYABLE,
                WxNoticeSendResult.officialFailure(new ExternalServiceException(ResultCode.WX_API_ERROR, "wechat", "-1", "busy")).outcome());
        assertEquals(WxNoticeSendResult.Outcome.UNKNOWN,
                WxNoticeSendResult.miniFailure(new ExternalServiceException(ResultCode.WX_API_ERROR, "wechat", "unknown")).outcome());
    }
}
