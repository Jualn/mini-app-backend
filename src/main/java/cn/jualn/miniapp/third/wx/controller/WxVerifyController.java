package cn.jualn.miniapp.third.wx.controller;

import cn.jualn.miniapp.third.wx.crypto.AesException;
import cn.jualn.miniapp.third.wx.crypto.WXBizMsgCrypt;
import cn.jualn.miniapp.module.wx.service.impl.WxEventServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 微信服务号接入与回调入口。
 */
//@Slf4j
//@RestController
//@RequestMapping("/wx")
//@RequiredArgsConstructor
//public class WxVerifyController {
//
//    private final WXBizMsgCrypt wxBizMsgCrypt;
//    private final WxEventServiceImpl wxEventServiceImpl;
//
//    /**
//     * 微信服务号接入验证
//     * 微信发 GET 请求，验证通过返回 echostr
//     * 只在首次配置时调用，之后不会再来
//     *
//     * @param msgSignature 微信签名
//     * @param timestamp 时间戳
//     * @param nonce 随机串
//     * @param echoStr 微信回传随机串
//     * @return 解密后的 echoStr
//     * @throws AesException 当签名验证或解密失败时抛出
//     */
//    @GetMapping("/callback")
//    public String verify(
//            @RequestParam("msg_signature") String msgSignature,
//            @RequestParam("timestamp") String timestamp,
//            @RequestParam("nonce") String nonce,
//            @RequestParam("echostr") String echoStr) throws AesException {
//        return wxBizMsgCrypt.verifyUrl(msgSignature, timestamp, nonce, echoStr);
//    }
//
//    /**
//     * 服务号
//     * 微信事件/消息回调（POST）
//     * 用户关注、发消息等都走这个地址
//     *
//     * @param msgSignature 微信签名
//     * @param timestamp 时间戳
//     * @param nonce 随机串
//     * @param postData 微信推送的加密 XML
//     * @return 固定返回 {@code success}，用于通知微信已处理
//     */
//    @PostMapping("/callback")
//    public String callback(@RequestParam("msg_signature") String msgSignature,
//                             @RequestParam("timestamp") String timestamp,
//                             @RequestParam("nonce") String nonce,
//                             // 微信推送的加密XML
//                             @RequestBody String postData) {
//        try {
//            String decryptedXml = wxBizMsgCrypt.decryptMsg(msgSignature, timestamp, nonce, postData);
//            return wxEventServiceImpl.handle(decryptedXml);
//
//        } catch (AesException e) {
//            log.error("微信回调解密失败", e);
//            // 按微信协议返回 success，避免重复回调风暴
//            return "success";
//        }
//    }
//}
