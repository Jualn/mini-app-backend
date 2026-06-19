package cn.jualn.miniapp.third.wx.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 服务号网页授权 code 换取 openid 的响应。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class MpOauthAccessTokenResponse extends WxApiResult {

    @JsonProperty("access_token")
    private String accessToken;

    @JsonProperty("expires_in")
    private Integer expiresIn;

    @JsonProperty("refresh_token")
    private String refreshToken;

    private String openid;

    private String scope;

    private String unionid;
}