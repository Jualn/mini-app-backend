package cn.jualn.miniapp.module.wx.service.impl;

import cn.jualn.miniapp.module.wx.dto.WxCallbackRequest;
import cn.jualn.miniapp.module.wx.service.WxCallbackService;
import cn.jualn.miniapp.module.wx.service.WxEventService;
import cn.jualn.miniapp.third.wx.config.WxAccountType;
import cn.jualn.miniapp.third.wx.config.WxCallbackAccount;
import cn.jualn.miniapp.third.wx.config.WxProperties;
import cn.jualn.miniapp.third.wx.crypto.AesException;
import cn.jualn.miniapp.third.wx.crypto.WXBizMsgCrypt;
import cn.jualn.miniapp.third.wx.crypto.WxSignatureService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Slf4j
@Service
@RequiredArgsConstructor
public class WxCallbackServiceImpl implements WxCallbackService {

    private static final int MAX_CALLBACK_BYTES = 256 * 1024;

    private final WxProperties wxProperties;
    private final WxEventService wxEventService;
    private final WxSignatureService wxSignatureService;

    /**
     * 微信服务器 URL 验证。
     * <p>
     * 注意：
     * 公众号/小程序服务器配置 GET 验证使用 signature，
     * 不使用 msg_signature，也不使用 WXBizMsgCrypt.verifyUrl()。
     */
    @Override
    public String verify(WxAccountType accountType, WxCallbackRequest request) {
        WxCallbackAccount account = wxProperties.getCallbackAccount(accountType);

        boolean valid = wxSignatureService.verify(
                account.token(),
                request.signature(),
                request.timestamp(),
                request.nonce()
        );

        if (!valid) {
            log.warn("微信 URL 验证失败，accountType={}", accountType);
            return "";
        }

        return request.echostr();
    }

    /**
     * 微信消息/事件回调。
     * 支持aes加密跟明文两种模式，优先使用aes加密模式。
     */
    @Override
    public String receive(WxAccountType accountType, WxCallbackRequest request, String postData) {
        if (postData == null || postData.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_CALLBACK_BYTES) {
            log.warn("微信回调 body 缺失或超过限制，accountType={}", accountType);
            return "FAIL";
        }
        if (postData.stripLeading().startsWith("&lt;")) {
            log.warn("微信回调 body 被 HTML 转义，疑似 XSS Filter 未排除");
        }

        WxCallbackAccount account = wxProperties.getCallbackAccount(accountType);

        try {
            String xmlBody;

            if (isEncrypted(request)) {
                WXBizMsgCrypt crypt = createCrypt(account);

                xmlBody = crypt.decryptMsg(
                        request.msgSignature(),
                        request.timestamp(),
                        request.nonce(),
                        postData
                );
            } else {
                boolean valid = wxSignatureService.verify(
                        account.token(),
                        request.signature(),
                        request.timestamp(),
                        request.nonce()
                );

                if (!valid) {
                    log.warn("微信明文消息签名验证失败，accountType={}", accountType);
                    return "FAIL";
                }

                xmlBody = postData;
            }

            String replyXml = wxEventService.handle(accountType, xmlBody);

            if (!StringUtils.hasText(replyXml) || "success".equalsIgnoreCase(replyXml)) {
                return "success";
            }

            if (isEncrypted(request)) {
                WXBizMsgCrypt crypt = createCrypt(account);
                return crypt.encryptMsg(
                        replyXml,
                        request.timestamp(),
                        request.nonce()
                );
            }

            return replyXml;
        } catch (AesException e) {
            log.warn("微信回调加解密失败，accountType={}, code={}", accountType, e.getCode());
            return "FAIL";
        } catch (Exception e) {
            log.error("微信回调处理异常，accountType={}", accountType, e);
            return "FAIL";
        }
    }

    private boolean isEncrypted(WxCallbackRequest request) {
        return "aes".equalsIgnoreCase(request.encryptType())
                || StringUtils.hasText(request.msgSignature());
    }

    private WXBizMsgCrypt createCrypt(WxCallbackAccount account) throws AesException {
        return new WXBizMsgCrypt(
                account.token(),
                account.encodingAesKey(),
                account.appId()
        );
    }
}
