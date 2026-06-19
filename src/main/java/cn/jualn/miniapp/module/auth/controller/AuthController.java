package cn.jualn.miniapp.module.auth.controller;

import cn.jualn.miniapp.common.result.Result;
import cn.jualn.miniapp.module.auth.dto.LoginRequest;
import cn.jualn.miniapp.module.auth.dto.LoginVO;
import cn.jualn.miniapp.module.auth.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * 认证入口控制器。
 * <p>
 * 对外提供小程序登录入口：接收 wx.login 的 code，返回 Sa-Token token。
 */
@Slf4j
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/auth")
public class AuthController {

    private final AuthService authService;

    /**
     * 小程序登录入口。
     * 在这个接口返回后，响应体中会包含一个cookie（默认名为 "satoken"），
     * 前端需要将其保存到本地（如 localStorage）并在可以后续请求中放入 header "Authorization
     * 跟接口响应的Token值是同一个，后续接口调用时需要在 header 中携带 Authorization:{token} 来进行认证。
     *
     * @param req 前端 wx.login 返回的一次性 code
     * @return loginRespVO token对象
     */
    @PostMapping({"", "/login"})
    public Result<LoginVO> login(@Valid @RequestBody LoginRequest req) {
        return Result.ok(authService.login(req.getCode()));
    }
}
