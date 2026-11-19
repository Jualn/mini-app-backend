package cn.jualn.miniapp.module.auth.controller;

import cn.jualn.miniapp.common.result.Result;
import cn.jualn.miniapp.module.auth.dto.LoginRequest;
import cn.jualn.miniapp.module.auth.vo.LoginVO;
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
    private final cn.jualn.miniapp.module.auth.service.LoginAttemptLimiter attempts;

    @PostMapping("/logout")
    public org.springframework.http.ResponseEntity<Void> logout() {
        authService.logout();
        return org.springframework.http.ResponseEntity.noContent()
                .cacheControl(org.springframework.http.CacheControl.noStore()).build();
    }

    /**
     * 小程序登录入口。
     * Token仅从响应体交给客户端；后续仅接受Authorization: Bearer头。
     *
     * @param req 前端 wx.login 返回的一次性 code
     * @return loginRespVO token对象
     */
    @PostMapping({"", "/login"})
    public Result<LoginVO> login(@Valid @RequestBody LoginRequest req, jakarta.servlet.http.HttpServletRequest request) {
        attempts.require(cn.jualn.miniapp.web.RequestPeer.address(request));
        return Result.ok(authService.login(req.getCode()));
    }
}
