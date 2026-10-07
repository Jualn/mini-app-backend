package cn.jualn.miniapp.module.wx.controller;

import cn.jualn.miniapp.module.wx.service.WxMpOauthService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/**
 * 服务号 H5 网页授权入口。
 */
@RestController
@RequestMapping("/third/wx/mp-oauth")
@RequiredArgsConstructor
public class WxMpOauthController {

    private final WxMpOauthService wxMpOauthService;

    /**
     * 服务号菜单“开启通知”入口。
     */
    @GetMapping("/subscribe/start")
    public void subscribeStart(HttpServletResponse response) throws IOException {
        response.sendRedirect(wxMpOauthService.buildSubscribeOauthUrl());
    }

    /**
     * 微信网页授权回调。
     */
    @GetMapping("/callback")
    public void callback(
            @RequestParam String code,
            @RequestParam String state,
            HttpServletResponse response
    ) throws IOException {
        response.sendRedirect(wxMpOauthService.handleCallback(code, state));
    }
}
