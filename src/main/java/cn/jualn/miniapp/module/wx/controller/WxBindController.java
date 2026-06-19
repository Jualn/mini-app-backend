package cn.jualn.miniapp.module.wx.controller;

import cn.jualn.miniapp.common.result.Result;
import cn.jualn.miniapp.third.wx.service.WxBindService;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 小程序与服务号绑定接口。
 */
@Validated
@RestController
@RequestMapping("/v1/wx/bind")
@RequiredArgsConstructor
public class WxBindController {

    private final WxBindService wxBindService;

    /**
     * 生成绑定二维码。
     * 后续可以改造成从登录态读取 userId，这里先保留显式参数便于联调。
     *
     * @param userId 小程序用户 ID
     * @return 绑定二维码结果
     */
    @GetMapping("/qrcode")
    public Result<WxBindService.BindQrInfo> createBindQr(@RequestParam @NotNull Long userId) {
        return Result.ok(wxBindService.createBindQr(userId));
    }
}

