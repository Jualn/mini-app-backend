package cn.jualn.miniapp.third.wx.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 小程序 access_token 返回体。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class MiniAccessTokenResponse extends WxApiResult {

    @JsonAlias("access_token")
    private String accessToken;

    @JsonAlias("expires_in")
    private Integer expiresIn;
}
