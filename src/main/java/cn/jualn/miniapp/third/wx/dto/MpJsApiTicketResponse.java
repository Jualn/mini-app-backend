package cn.jualn.miniapp.third.wx.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 服务号 JS-SDK ticket 响应。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class MpJsApiTicketResponse extends WxApiResult {

    private String ticket;

    @JsonProperty("expires_in")
    private Integer expiresIn;
}