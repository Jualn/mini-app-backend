package cn.jualn.miniapp.module.user.controller;

import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.module.user.bo.EffectiveProfileBO;
import cn.jualn.miniapp.module.user.dto.request.EffectiveProfileUpdateRequest;
import cn.jualn.miniapp.module.user.vo.EffectiveProfileVO;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;

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

    @GetMapping("/me/profile")
    public ResponseEntity<EffectiveProfileVO> getMyProfile() {
        return effectiveResponse(userService.getEffectiveProfile(null));
    }

    @GetMapping("/{userId}/profile")
    public ResponseEntity<EffectiveProfileVO> getUserProfile(
            @PathVariable String userId) {
        Long id;
        try {
            id = Long.valueOf(userId);
        } catch (NumberFormatException invalid) {
            throw new BusinessException(
                    ResultCode.USER_NOT_FOUND);
        }
        return effectiveResponse(userService.getEffectiveProfile(id));
    }

    @PostMapping("/me/profile")
    public ResponseEntity<EffectiveProfileVO> updateMyProfile(
            @RequestBody EffectiveProfileUpdateRequest request) {
        return effectiveResponse(userService.updateEffectiveProfile(request.toCommand()));
    }

    private ResponseEntity<EffectiveProfileVO> effectiveResponse(
            EffectiveProfileBO profile) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new EffectiveProfileVO(String.valueOf(profile.getUserId()),
                        profile.getNickname(), profile.getAvatarUrl(), profile.getBackgroundUrl(), profile.getBio(), profile.isPlatformOperator()));
    }

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
    public Result<UserProfileVO> updateCurrentProfile(@Valid @RequestBody UserProfileUpdateRequest req) {
        return Result.ok(userConverter.toVO(userService.updateCurrentProfile(userConverter.toUpdateBO(req))));
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
