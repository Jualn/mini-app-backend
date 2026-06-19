package cn.jualn.miniapp.third.wx.controller;

import cn.jualn.miniapp.third.wx.service.WxMpOauthService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.Map;

/**
 * 服务号 H5 网页授权入口。
 * <p>
 * 两条主路径：
 * 1. 小程序通知设置页：/bind/start?token=xxx
 * 只负责绑定 mpOpenid 到 userId，不拉起 wx-open-subscribe。
 * <p>
 * 2. 服务号菜单：/subscribe/start
 * 只负责根据 mpOpenid 查 userId，查到后进入 wx-open-subscribe。
 */
@RestController
@RequestMapping("/third/wx/mp-oauth")
@RequiredArgsConstructor
public class WxMpOauthController {

    private final WxMpOauthService wxMpOauthService;

    /**
     * 小程序侧绑定服务号入口。
     * <p>
     * 小程序 web-view 打开：
     * /third/wx/mp-oauth/bind/start?token=xxx
     */
    @GetMapping("/bind/start")
    public void bindStart(
            @RequestParam String token,
            HttpServletResponse response
    ) throws IOException {
        response.sendRedirect(wxMpOauthService.buildBindOauthUrl(token));
    }

    /**
     * 兼容旧入口。
     * <p>
     * 原来的 /start?token=xxx 继续作为绑定入口使用，
     * 避免小程序端未同步更新时直接失效。
     */
    @GetMapping("/start")
    public void start(
            @RequestParam String token,
            HttpServletResponse response
    ) throws IOException {
        response.sendRedirect(wxMpOauthService.buildBindOauthUrl(token));
    }

    /**
     * 服务号菜单“开启通知”入口。
     * <p>
     * 公众号菜单 URL 配置：
     * https://你的域名/third/wx/mp-oauth/subscribe/start
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

    /**
     * 小程序通知设置页可选接口：
     * 用于判断当前用户是否已绑定服务号。
     */
    @GetMapping("/bind/status")
    public Map<String, Object> bindStatus(@RequestParam String token) {
        return wxMpOauthService.getBindStatus(token);
    }
}