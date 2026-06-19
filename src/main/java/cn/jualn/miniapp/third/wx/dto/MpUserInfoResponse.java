package cn.jualn.miniapp.third.wx.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 服务号用户信息返回体。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class MpUserInfoResponse extends WxApiResult {

    private Integer subscribe;

    private String openid;

    private String nickname;

    @JsonAlias("headimgurl")
    private String headImgUrl;

    private String unionid;
}

