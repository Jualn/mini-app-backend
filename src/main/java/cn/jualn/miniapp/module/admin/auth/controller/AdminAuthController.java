package cn.jualn.miniapp.module.admin.auth.controller;

import cn.jualn.miniapp.common.result.Result;
import cn.jualn.miniapp.module.admin.auth.service.AdminTokenService;
import cn.jualn.miniapp.module.admin.auth.vo.AdminIdentityVO;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/admin/auth")
public class AdminAuthController {

    private final AdminTokenService adminTokenService;

    @GetMapping("/me")
    public Result<AdminIdentityVO> getCurrentIdentity() {
        return Result.ok(adminTokenService.requireValidLogin());
    }

    @PostMapping("/logout")
    public Result<Void> logoutCurrent() {
        adminTokenService.logoutCurrent();
        return Result.ok(null);
    }
}
