package cn.jualn.miniapp.third.wx.dto;

import lombok.Builder;
import lombok.Data;

/**
 * H5 页面初始化微信 JS-SDK 所需配置。
 */
@Data
@Builder
public class WxJsSdkConfigVO {

    private String appId;

    private Long timestamp;

    private String nonceStr;

    private String signature;
}