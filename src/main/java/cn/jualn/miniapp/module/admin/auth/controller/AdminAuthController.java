package cn.jualn.miniapp.module.admin.auth.controller;

import cn.jualn.miniapp.common.result.Result;
import cn.jualn.miniapp.common.security.AdminStpUtil;
import cn.jualn.miniapp.module.admin.auth.converter.AdminQrLoginConverter;
import cn.jualn.miniapp.module.admin.auth.service.AdminTokenService;
import cn.jualn.miniapp.module.admin.auth.support.AdminBrowserSession;
import cn.jualn.miniapp.module.admin.auth.vo.AdminIdentityVO;
import cn.jualn.miniapp.module.admin.auth.vo.qrlogin.AdminQrLoginResultVO;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/admin/auth")
public class AdminAuthController {

    private final AdminTokenService adminTokenService;
    private final AdminQrLoginConverter qrLoginConverter;

    @GetMapping("/me")
    public Result<AdminIdentityVO> getCurrentIdentity() {
        return Result.ok(adminTokenService.requireValidLogin());
    }

    @PostMapping(value = AdminBrowserSession.ROUTE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AdminQrLoginResultVO> resumeBrowserSession(
            @CookieValue(name = AdminBrowserSession.COOKIE, required = false) String token,
            @RequestBody(required = false) byte[] body,
            HttpServletRequest request) {
        AdminBrowserSession.requireSameOrigin(request);
        if (body != null && body.length != 0)
            throw cn.jualn.miniapp.common.exception.ContractProblemException.validation(
                    new cn.jualn.miniapp.common.exception.ContractProblemException.Violation(
                            "body", "/", "NOT_ALLOWED", "Request body is not accepted"));
        if (token == null || token.isBlank()) return ResponseEntity.noContent().build();
        return ResponseEntity.ok(qrLoginConverter.result(adminTokenService.resumeBrowserSession(token)));
    }

    @PostMapping("/logout")
    public ResponseEntity<Result<Void>> logoutCurrent(
            @CookieValue(name = AdminBrowserSession.COOKIE, required = false) String browserToken,
            HttpServletRequest request) {
        boolean clearCookie = browserToken != null && AdminBrowserSession.sameOriginRequest(request)
                && browserToken.equals(AdminStpUtil.STP_LOGIC.getTokenValue());
        adminTokenService.logoutCurrent();
        var response = ResponseEntity.ok();
        if (clearCookie)
            response.header(HttpHeaders.SET_COOKIE, AdminBrowserSession.cookie("", 0).toString());
        return response.body(Result.ok(null));
    }
}
