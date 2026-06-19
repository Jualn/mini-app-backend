package cn.jualn.miniapp.module.wx.dto;

public record WxCallbackRequest(
        String signature,
        String msgSignature,
        String timestamp,
        String nonce,
        String echostr,
        String encryptType
) {
}
