package cn.jualn.miniapp.third.wx.controller;

import cn.jualn.miniapp.third.wx.dto.WxJsSdkConfigVO;
import cn.jualn.miniapp.third.wx.service.WxJsSdkService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 微信 JS-SDK 配置接口。
 */
@RestController
@RequestMapping("/third/wx")
@RequiredArgsConstructor
public class WxJsSdkController {

    private final WxJsSdkService wxJsSdkService;

    /**
     * 获取 H5 当前 URL 对应的 JS-SDK 签名。
     *
     * @param url H5 页面当前完整 URL，前端传 location.href 去掉 hash 的部分
     */
    @GetMapping("/js-sdk-config")
    public WxJsSdkConfigVO getJsSdkConfig(@RequestParam String url) {
        return wxJsSdkService.createConfig(url);
    }
}