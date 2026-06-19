package cn.jualn.miniapp.third.wx.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 服务号订阅通知发送响应。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class MpSubscribeMessageResponse extends WxApiResult {

    @JsonProperty("msgid")
    private Long msgId;
}