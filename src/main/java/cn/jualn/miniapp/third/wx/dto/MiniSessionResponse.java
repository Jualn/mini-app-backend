package cn.jualn.miniapp.third.wx.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 小程序登录 code2session 返回体。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class MiniSessionResponse extends WxApiResult {

    private String openid;

    @JsonAlias("session_key")
    private String sessionKey;

    private String unionid;
}

