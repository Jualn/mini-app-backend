package cn.jualn.miniapp.module.wx.service;

import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.exception.SystemException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.third.wx.client.WxClient;
import cn.jualn.miniapp.third.wx.config.WxProperties;
import cn.jualn.miniapp.third.wx.dto.WxJsSdkConfigVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.net.URISyntaxException;
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
        String signingUrl = validateSigningUrl(url);

        String ticket = wxClient.getMpJsApiTicket(false);
        String nonceStr = UUID.randomUUID().toString().replace("-", "");
        long timestamp = System.currentTimeMillis() / 1000;

        String raw = "jsapi_ticket=" + ticket
                + "&noncestr=" + nonceStr
                + "&timestamp=" + timestamp
                + "&url=" + signingUrl;

        // 注意：微信 JS-SDK 要的是 SHA1，不是 MD5。
        String signature = sha1(raw);

        return WxJsSdkConfigVO.builder()
                .appId(wxProperties.getMp().getAppId())
                .timestamp(timestamp)
                .nonceStr(nonceStr)
                .signature(signature)
                .build();
    }

    private String validateSigningUrl(String url) {
        if (!StringUtils.hasText(url)) {
            throw new BusinessException(ResultCode.WX_JS_SDK_URL_INVALID, "url 不能为空");
        }
        try {
            URI requested = new URI(url);
            URI allowed = new URI(wxProperties.getMp().getDomain());
            boolean sameOrigin = "https".equalsIgnoreCase(requested.getScheme())
                    && requested.getUserInfo() == null
                    && requested.getHost() != null
                    && requested.getHost().equalsIgnoreCase(allowed.getHost())
                    && effectivePort(requested) == effectivePort(allowed);
            if (!sameOrigin || requested.getFragment() != null) {
                throw new BusinessException(ResultCode.WX_JS_SDK_URL_INVALID,
                        "url 必须是项目服务号域名下且不含 fragment 的 HTTPS 地址");
            }
            return requested.toASCIIString();
        } catch (URISyntaxException invalid) {
            throw new BusinessException(ResultCode.WX_JS_SDK_URL_INVALID, "url 格式无效");
        }
    }

    private int effectivePort(URI uri) {
        if (uri.getPort() >= 0) return uri.getPort();
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
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
            throw new SystemException("生成微信 JS-SDK 签名失败", e);
        }
    }
}
