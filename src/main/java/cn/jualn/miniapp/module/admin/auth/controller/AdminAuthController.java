package cn.jualn.miniapp.module.admin.auth.controller;

import cn.dev33.satoken.stp.StpUtil;
import cn.jualn.miniapp.common.result.Result;
import cn.jualn.miniapp.module.admin.auth.service.AdminAuthService;
import cn.jualn.miniapp.module.admin.auth.vo.AdminIdentityVO;
import cn.jualn.miniapp.module.admin.auth.vo.AdminQrConfirmationVO;
import cn.jualn.miniapp.module.admin.auth.vo.AdminQrSessionCreateVO;
import cn.jualn.miniapp.module.admin.auth.vo.AdminQrSessionPollVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/admin/auth")
public class AdminAuthController {

    private static final String SESSION_ID_PATTERN = "[A-Za-z0-9_-]{22,64}";
    private static final String POLL_SECRET_PATTERN = "[A-Za-z0-9_-]{43}";

    private final AdminAuthService adminAuthService;

    @PostMapping("/qr-sessions")
    public Result<AdminQrSessionCreateVO> createQrSession(HttpServletRequest request) {
        return Result.ok(adminAuthService.createQrSession(request.getRemoteAddr()));
    }

    @GetMapping("/qr-sessions/{sessionId}")
    public Result<AdminQrSessionPollVO> pollQrSession(
            @PathVariable @Pattern(regexp = SESSION_ID_PATTERN, message = "无效的扫码会话标识") String sessionId,
            @RequestHeader("X-Admin-Login-Secret")
            @Pattern(regexp = POLL_SECRET_PATTERN, message = "无效的轮询密钥") String pollSecret) {
        return Result.ok(adminAuthService.pollQrSession(sessionId, pollSecret));
    }

    @DeleteMapping("/qr-sessions/{sessionId}")
    public Result<Void> cancelQrSession(
            @PathVariable @Pattern(regexp = SESSION_ID_PATTERN, message = "无效的扫码会话标识") String sessionId,
            @RequestHeader("X-Admin-Login-Secret")
            @Pattern(regexp = POLL_SECRET_PATTERN, message = "无效的轮询密钥") String pollSecret) {
        adminAuthService.cancelQrSession(sessionId, pollSecret);
        return Result.ok(null);
    }

    @PostMapping("/qr-confirmations/{sessionId}")
    public Result<AdminQrConfirmationVO> confirmQrSession(
            @PathVariable @Pattern(regexp = SESSION_ID_PATTERN, message = "无效的扫码会话标识") String sessionId) {
        return Result.ok(adminAuthService.confirmQrSession(sessionId, StpUtil.getLoginIdAsLong()));
    }

    @GetMapping("/me")
    public Result<AdminIdentityVO> getCurrentIdentity() {
        return Result.ok(adminAuthService.getCurrentIdentity());
    }

    @PostMapping("/logout")
    public Result<Void> logoutCurrent() {
        adminAuthService.logoutCurrent();
        return Result.ok(null);
    }
}
