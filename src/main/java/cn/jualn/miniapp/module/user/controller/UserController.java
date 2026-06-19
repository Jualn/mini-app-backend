package cn.jualn.miniapp.module.user.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.jualn.miniapp.common.result.Result;
import cn.jualn.miniapp.module.user.converter.UserConverter;
import cn.jualn.miniapp.module.user.dto.request.UserAgreementRequest;
import cn.jualn.miniapp.module.user.dto.request.UserProfileUpdateRequest;
import cn.jualn.miniapp.module.user.entity.UserAgreement;
import cn.jualn.miniapp.module.user.service.UserService;
import cn.jualn.miniapp.module.user.vo.UserAgreementStatusVO;
import cn.jualn.miniapp.module.user.vo.UserPublicProfileVO;
import cn.jualn.miniapp.module.user.vo.UserProfileVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * 用户资料控制器。
 * <p>
 * 提供当前登录用户资料查询与更新能力。
 */
@Validated
@RestController
@RequestMapping("/v1/users")
@RequiredArgsConstructor
@Tag(name = "用户资料")
public class UserController {

    private final UserService userService;
    private final UserConverter userConverter;

    /**
     * 获取当前登录用户资料。
     *
     * @return 当前用户资料
     */
    @SaCheckLogin
    @Operation(summary = "获取当前用户资料")
    @GetMapping("/me")
    public Result<UserProfileVO> getCurrentProfile() {
        return Result.ok(userConverter.toVO(userService.getCurrentProfile()));
    }

    /**
     * 获取用户公开资料。
     *
     * @param userId 目标用户 ID
     * @return 用户公开资料
     */
    @Parameters({
            @Parameter(name = "userId", description = "目标用户 ID", required = true, example = "123")
    })
    @GetMapping("/public/{userId}")
    public Result<UserPublicProfileVO> getPublicProfile(@PathVariable @NotNull Long userId) {
        return Result.ok(userConverter.toPublicRespVO(userService.getPublicProfile(userId)));
    }

    /**
     * 更新当前登录用户资料。
     *
     * @param req 资料更新请求
     * @return 更新结果
     */
    @PutMapping("/me")
    public Result<String> updateCurrentProfile(@Valid @RequestBody UserProfileUpdateRequest req) {
        userService.updateCurrentProfile(userConverter.toUpdateBO(req));
        return Result.ok(null);
    }

    /**
     * 当前登录用户同意协议。
     *
     * @param req 协议同意请求
     * @return 提交结果
     */
    @PostMapping("/me/agreement")
    public Result<String> agreeAgreement(@Valid @RequestBody UserAgreementRequest req) {
        userService.agreeCurrentAgreement(req.getVersion());
        return Result.ok(null);
    }

    /**
     * 获取当前登录用户协议同意状态。
     *
     * @return 协议状态
     */
    @GetMapping("/me/agreement")
    public Result<UserAgreementStatusVO> getCurrentAgreementStatus() {
        UserAgreement agreement = userService.getCurrentAgreement();
        if (agreement == null) {
            return Result.ok(UserAgreementStatusVO.builder().agreed(false).build());
        }
        return Result.ok(userConverter.toAgreementStatusRespVO(agreement));
    }

}
