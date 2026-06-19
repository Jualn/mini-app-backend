package cn.jualn.miniapp.third.wx.notice;

import lombok.Builder;
import lombok.Data;

/**
 * H5 订阅页展示用模板信息。
 */
@Data
@Builder
public class WxMpNoticeTemplateView {

    private String type;

    private String name;

    private String templateId;
}