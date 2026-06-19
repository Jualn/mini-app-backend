package cn.jualn.miniapp.module.wx.controller;

import cn.jualn.miniapp.module.wx.dto.WxCallbackRequest;
import cn.jualn.miniapp.module.wx.service.WxCallbackService;
import cn.jualn.miniapp.third.wx.config.WxAccountType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/wx")
public class WxCallbackController {

    private final WxCallbackService wxCallbackService;

    /**
     * 服务号服务器接入验证。
     */
    @GetMapping(value = "/mp/callback", produces = MediaType.TEXT_PLAIN_VALUE)
    public String verifyMp(
            @RequestParam(required = false) String signature,
            @RequestParam(required = false, name = "msg_signature") String msgSignature,
            @RequestParam String timestamp,
            @RequestParam String nonce,
            @RequestParam String echostr
    ) {
        return wxCallbackService.verify(
                WxAccountType.MP,
                new WxCallbackRequest(signature, msgSignature, timestamp, nonce, echostr, null)
        );
    }

    /**
     * 服务号消息推送。
     */
    @PostMapping(value = "/mp/callback", produces = MediaType.TEXT_PLAIN_VALUE)
    public String receiveMp(
            @RequestParam(required = false) String signature,
            @RequestParam(required = false, name = "msg_signature") String msgSignature,
            @RequestParam String timestamp,
            @RequestParam String nonce,
            @RequestParam(required = false, name = "encrypt_type") String encryptType,
            @RequestBody String postData
    ) {
        try {
            return wxCallbackService.receive(
                    WxAccountType.MP,
                    new WxCallbackRequest(signature, msgSignature, timestamp, nonce, null, encryptType),
                    postData
            );
        } catch (Exception e) {
            log.error("WeChat Mp callback error", e);
            return "FAIL";
        }
    }

    /**
     * 小程序服务器接入验证。
     */
    @GetMapping(value = "/ma/callback", produces = MediaType.TEXT_PLAIN_VALUE)
    public String verifyMa(
            @RequestParam(required = false) String signature,
            @RequestParam(required = false, name = "msg_signature") String msgSignature,
            @RequestParam String timestamp,
            @RequestParam String nonce,
            @RequestParam String echostr
    ) {
        return wxCallbackService.verify(
                WxAccountType.MA,
                new WxCallbackRequest(signature, msgSignature, timestamp, nonce, echostr, null)
        );
    }

    /**
     * 小程序消息推送。
     */
    @PostMapping(value = "/ma/callback", produces = MediaType.TEXT_PLAIN_VALUE)
    public String receiveMa(
            @RequestParam(required = false) String signature,
            @RequestParam(required = false, name = "msg_signature") String msgSignature,
            @RequestParam String timestamp,
            @RequestParam String nonce,
            @RequestParam(required = false, name = "encrypt_type") String encryptType,
            @RequestBody String postData
    ) {
        try {
            return wxCallbackService.receive(
                    WxAccountType.MA,
                    new WxCallbackRequest(signature, msgSignature, timestamp, nonce, null, encryptType),
                    postData
            );

        } catch (Exception e) {
            log.error("WeChat Ma callback error", e);
            return "FAIL";
        }
    }
}
