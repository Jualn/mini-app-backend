package cn.jualn.miniapp.module.wx.service;

import cn.jualn.miniapp.module.wx.dto.WxCallbackRequest;
import cn.jualn.miniapp.third.wx.config.WxAccountType;

public interface WxCallbackService {
    String verify(WxAccountType accountType, WxCallbackRequest request);

    String receive(WxAccountType accountType, WxCallbackRequest request, String postData);
}
