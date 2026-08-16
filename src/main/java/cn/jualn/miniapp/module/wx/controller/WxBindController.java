package cn.jualn.miniapp.module.wx.controller;

import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.Result;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.third.wx.service.WxBindService;
import cn.jualn.miniapp.third.wx.service.WxMpOauthService;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 小程序与服务号绑定接口。
 */
@Validated
@RestController
@RequestMapping("/v1/wx/bind")
@RequiredArgsConstructor
public class WxBindController {

    private final WxBindService wxBindService;
    private final WxMpOauthService wxMpOauthService;

    @GetMapping("/qrcode")
    public Result<WxBindService.BindQrInfo> createBindQr() {
        return Result.ok(wxBindService.createBindQr(requireCurrentUserId()));
    }

    @GetMapping("/oauth-url")
    public Result<Map<String, String>> createBindOauthUrl() {
        String url = wxMpOauthService.buildBindOauthUrl(requireCurrentUserId());
        return Result.ok(Map.of("url", url));
    }

    @GetMapping("/status")
    public Result<Map<String, Object>> bindStatus() {
        return Result.ok(wxMpOauthService.getBindStatus(requireCurrentUserId()));
    }

    private Long requireCurrentUserId() {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        return userId;
    }
}
