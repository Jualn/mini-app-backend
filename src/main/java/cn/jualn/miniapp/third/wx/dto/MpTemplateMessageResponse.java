package cn.jualn.miniapp.third.wx.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 服务号模板消息发送返回体。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class MpTemplateMessageResponse extends WxApiResult {

    private Long msgid;
}

