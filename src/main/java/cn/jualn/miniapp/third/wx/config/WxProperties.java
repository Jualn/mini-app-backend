package cn.jualn.miniapp.third.wx.config;

import jakarta.validation.Valid;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter
@Validated
@RequiredArgsConstructor
@ConfigurationProperties(prefix = "wx")
public class WxProperties {

    @Valid
    private final Mp mp;
    @Valid
    private final Ma ma;

    public WxCallbackAccount getCallbackAccount(WxAccountType type) {
        return switch (type) {
            case MP -> new WxCallbackAccount(
                    mp.getAppId(),
                    mp.getToken(),
                    mp.getEncodingAesKey()
            );
            case MA -> new WxCallbackAccount(
                    ma.getAppId(),
                    ma.getToken(),
                    ma.getEncodingAesKey()
            );
        };
    }

    @Getter
    @RequiredArgsConstructor
    public static class Mp {

        /**
         * 服务号 AppID。
         */
        private final String appId;

        /**
         * 服务号 AppSecret。
         */
        private final String appSecret;
        /**
         * 开发者中心设置的令牌（Token），用于验证消息真实性 TODO 暂未使用
         */
        @Deprecated
        private final String token;
        /**
         * 32字节Base64 TODO 暂未使用
         */
        @Deprecated
        private final String encodingAesKey;

        /**
         * 当前后端公网域名，例如 https: //api.xxx.com。
         * 用于拼接网页授权 callback 和 H5 静态页地址。
         */
        private final String domain;

    }

    @Getter
    @RequiredArgsConstructor
    public static class Ma {

        /**
         * 小程序 AppID。
         */
        private final String appId;

        /**
         * 小程序 AppSecret。
         */
        private final String appSecret;

        /**
         * 小程序消息推送 Token
         */
        private final String token;

        /**
         * 小程序消息推送 EncodingAESKey，43位字符组成，字符范围为A-Z,a-z,0-9
         */
        private final String encodingAesKey;
    }
}
