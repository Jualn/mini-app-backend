package cn.jualn.miniapp.third.wx.service;

import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.third.wx.client.WxClient;
import cn.jualn.miniapp.third.wx.config.WxProperties;
import cn.jualn.miniapp.third.wx.dto.WxJsSdkConfigVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 服务号 JS-SDK 签名服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WxJsSdkService {

    private final WxClient wxClient;
    private final WxProperties wxProperties;

    /**
     * 创建微信 JS-SDK 配置。
     *
     * @param url 当前 H5 页面 URL，必须与前端 wx.config 时的页面 URL 一致
     * @return JS-SDK 配置
     */
    public WxJsSdkConfigVO createConfig(String url) {
        if (!StringUtils.hasText(url)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "url 不能为空");
        }

        String ticket = wxClient.getMpJsApiTicket(false);
        String nonceStr = UUID.randomUUID().toString().replace("-", "");
        long timestamp = System.currentTimeMillis() / 1000;

        String raw = "jsapi_ticket=" + ticket
                + "&noncestr=" + nonceStr
                + "&timestamp=" + timestamp
                + "&url=" + url;

        // 注意：微信 JS-SDK 要的是 SHA1，不是 MD5。
        String signature= sha1(raw);

        return WxJsSdkConfigVO.builder()
                .appId(wxProperties.getMp().getAppId())
                .timestamp(timestamp)
                .nonceStr(nonceStr)
                .signature(signature)
                .build();
    }

    /**
     * SHA1 签名。
     */
    private String sha1(String raw) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-1");
            byte[] bytes = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte b : bytes) {
                String hex = Integer.toHexString(b & 0xff);
                if (hex.length() == 1) {
                    builder.append('0');
                }
                builder.append(hex);
            }
            return builder.toString();
        } catch (Exception e) {
            throw new BusinessException(ResultCode.SERVER_ERROR, "生成微信 JS-SDK 签名失败");
        }
    }
}