package cn.jualn.miniapp.third.wx.config;

/**
 * 微信回调账号配置项。
 *
 * @param appId   微信 AppID。
 * @param token  开发者中心设置的令牌（Token），用于验证消息真实性。
 * @param encodingAesKey 32字节Base64，用于消息加密和解密。
 */
public record WxCallbackAccount(
        String appId,
        String token,
        String encodingAesKey
) {
}
