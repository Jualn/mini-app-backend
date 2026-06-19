package cn.jualn.miniapp.module.wx.service;

import cn.jualn.miniapp.third.wx.config.WxAccountType;

public interface WxEventService {
    String handle(WxAccountType accountType, String xmlBody);
}
