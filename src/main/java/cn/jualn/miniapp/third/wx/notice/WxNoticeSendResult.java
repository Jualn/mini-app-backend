package cn.jualn.miniapp.third.wx.notice;

import cn.jualn.miniapp.common.exception.ExternalServiceException;
import cn.jualn.miniapp.third.wx.dto.MiniSubscribeMessageResponse;
import cn.jualn.miniapp.third.wx.dto.MpSubscribeMessageResponse;
import java.util.Set;

/** Provider outcome only. Contains no raw errmsg, HTTP cause, credentials or request body. */
public record WxNoticeSendResult(Outcome outcome, String providerCode, String messageId) {
    public enum Outcome { ACCEPTED, REJECTED_RETRYABLE, REJECTED_PERMANENT, UNKNOWN }
    private static final Set<String> OFFICIAL_RETRYABLE = Set.of("-1");
    private static final Set<String> MINI_RETRYABLE = Set.of("-1");

    public static WxNoticeSendResult official(MpSubscribeMessageResponse response) {
        return response == null ? unknown() : classify(response.getErrcode(),
                response.getMsgId() == null ? null : response.getMsgId().toString(), OFFICIAL_RETRYABLE);
    }
    public static WxNoticeSendResult mini(MiniSubscribeMessageResponse response) {
        return response == null ? unknown() : classify(response.getErrcode(), null, MINI_RETRYABLE);
    }
    public static WxNoticeSendResult officialFailure(ExternalServiceException failure) {
        return rejected(failure.getProviderCode(), OFFICIAL_RETRYABLE);
    }
    public static WxNoticeSendResult miniFailure(ExternalServiceException failure) {
        return rejected(failure.getProviderCode(), MINI_RETRYABLE);
    }
    public static WxNoticeSendResult unknown() { return new WxNoticeSendResult(Outcome.UNKNOWN, null, null); }
    private static WxNoticeSendResult classify(Integer code, String id, Set<String> retryable) {
        if (code == null) return unknown();
        return code == 0 ? new WxNoticeSendResult(Outcome.ACCEPTED, null, id) : rejected(code.toString(), retryable);
    }
    private static WxNoticeSendResult rejected(String code, Set<String> retryable) {
        if (code == null) return unknown();
        return new WxNoticeSendResult(retryable.contains(code) ? Outcome.REJECTED_RETRYABLE : Outcome.REJECTED_PERMANENT, code, null);
    }
}
