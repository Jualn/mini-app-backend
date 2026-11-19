package cn.jualn.miniapp.module.user.controller.admin;

import cn.jualn.miniapp.module.user.service.UserAccountService;
import cn.jualn.miniapp.common.web.AdminNamesVO;
import cn.jualn.miniapp.common.web.AdminQuery;

import cn.jualn.miniapp.common.result.Result;
import cn.jualn.miniapp.common.security.AdminStpUtil;
import cn.jualn.miniapp.module.admin.auth.support.AdminPermissionPolicy;
import cn.jualn.miniapp.module.user.converter.AdminUserConverter;
import cn.jualn.miniapp.module.user.dto.admin.AdminUserPageQuery;
import cn.jualn.miniapp.module.user.dto.admin.AdminUserRestrictionRequest;
import cn.jualn.miniapp.module.user.service.UserService;
import cn.jualn.miniapp.module.user.vo.admin.AdminUserDetailVO;
import cn.jualn.miniapp.module.user.vo.admin.AdminUserPageVO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/admin/users")
public class AdminUserController {

    private final UserService userService;
    private final UserAccountService userAccounts;
    private final AdminUserConverter adminUserConverter;

    @GetMapping("/names")
    public org.springframework.http.ResponseEntity<AdminNamesVO.Users> names(jakarta.servlet.http.HttpServletRequest request) {
        var ids = AdminQuery.values(request, "userId", 100);
        var at = java.time.Instant.now();
        var names = AdminQuery.read(() -> userService.adminUserNames(ids));
        return org.springframework.http.ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
                .body(new AdminNamesVO.Users(at, names.stream().map(AdminNamesVO::user).toList()));
    }

    @GetMapping
    public Result<AdminUserPageVO> pageUsers(@Valid AdminUserPageQuery query) {
        AdminStpUtil.STP_LOGIC.checkPermission(AdminPermissionPolicy.USER_READ);
        return Result.ok(adminUserConverter.toPageVO(
                userService.pageAdminUsers(adminUserConverter.toQueryBO(query))));
    }

    @GetMapping("/{id}")
    public Result<AdminUserDetailVO> getUser(
            @PathVariable @Positive(message = "用户ID必须大于0") Long id) {
        AdminStpUtil.STP_LOGIC.checkPermission(AdminPermissionPolicy.USER_READ);
        return Result.ok(adminUserConverter.toDetailVO(userService.getAdminUserDetail(id)));
    }

    @PostMapping("/{id}/mute")
    public Result<Void> muteUser(
            @PathVariable @Positive(message = "用户ID必须大于0") Long id,
            @Valid @RequestBody AdminUserRestrictionRequest request) {
        AdminStpUtil.STP_LOGIC.checkPermission(AdminPermissionPolicy.USER_MANAGE);
        userAccounts.muteUser(adminUserConverter.toRestrictionBO(
                AdminStpUtil.STP_LOGIC.getLoginIdAsLong(), id, request));
        return Result.ok(null);
    }

    @PostMapping("/{id}/ban")
    public Result<Void> banUser(
            @PathVariable @Positive(message = "用户ID必须大于0") Long id,
            @Valid @RequestBody AdminUserRestrictionRequest request) {
        AdminStpUtil.STP_LOGIC.checkPermission(AdminPermissionPolicy.USER_MANAGE);
        userAccounts.banUser(adminUserConverter.toRestrictionBO(
                AdminStpUtil.STP_LOGIC.getLoginIdAsLong(), id, request));
        return Result.ok(null);
    }

    @PostMapping("/{id}/restore")
    public Result<Void> restoreUser(
            @PathVariable @Positive(message = "用户ID必须大于0") Long id,
            @Valid @RequestBody AdminUserRestrictionRequest request) {
        AdminStpUtil.STP_LOGIC.checkPermission(AdminPermissionPolicy.USER_MANAGE);
        userAccounts.restoreUser(adminUserConverter.toRestrictionBO(
                AdminStpUtil.STP_LOGIC.getLoginIdAsLong(), id, request));
        return Result.ok(null);
    }

}
