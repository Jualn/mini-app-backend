package cn.jualn.miniapp.third.wx.dto;

import lombok.Data;

/**
 * H5 wx-open-subscribe 回传结果。
 * <p>
 * 该结果只用于记录用户点击过订阅入口，不作为后续能否发送的强依据。
 * 真正发送时仍以微信接口返回结果为准。
 * </p>
 */
@Data
public class WxMpSubscribeResultDTO {

    /**
     * OAuth 完成后下发给 H5 的短期 state。
     */
    private String state;

    /**
     * wx-open-subscribe 是否触发 success。
     */
    private Boolean success;

    /**
     * 微信 success 回调原始 detail。
     */
    private Object detail;

    /**
     * 微信 error 回调原始 error。
     */
    private Object error;
}