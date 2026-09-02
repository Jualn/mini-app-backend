package cn.jualn.miniapp.module.content.controller.admin;

import cn.jualn.miniapp.common.result.Result;
import cn.jualn.miniapp.common.security.AdminStpUtil;
import cn.jualn.miniapp.module.admin.auth.support.AdminPermissionPolicy;
import cn.jualn.miniapp.module.content.converter.AdminContentConverter;
import cn.jualn.miniapp.module.content.dto.admin.AdminContentActionRequest;
import cn.jualn.miniapp.module.content.dto.admin.AdminContentPageQuery;
import cn.jualn.miniapp.module.content.service.AdminContentService;
import cn.jualn.miniapp.module.content.vo.admin.AdminContentDetailVO;
import cn.jualn.miniapp.module.content.vo.admin.AdminContentPageVO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/admin/contents")
public class AdminContentController {

    private static final String TYPE_PATTERN = "post|comment";
    private static final String ACTION_PATTERN = "pin|unpin|feature|unfeature|take-down|restore";

    private final AdminContentService adminContentService;
    private final AdminContentConverter adminContentConverter;

    @GetMapping
    public Result<AdminContentPageVO> pageContents(@Valid AdminContentPageQuery query) {
        AdminStpUtil.STP_LOGIC.checkPermission(AdminPermissionPolicy.CONTENT_READ);
        return Result.ok(adminContentConverter.toPageVO(
                adminContentService.pageContents(adminContentConverter.toQueryBO(query))));
    }

    @GetMapping("/{type}/{id}")
    public Result<AdminContentDetailVO> getContentDetail(
            @PathVariable @Pattern(regexp = TYPE_PATTERN, message = "内容类型不合法") String type,
            @PathVariable @Positive(message = "内容ID必须大于0") Long id) {
        AdminStpUtil.STP_LOGIC.checkPermission(AdminPermissionPolicy.CONTENT_READ);
        return Result.ok(adminContentConverter.toDetailVO(
                adminContentService.getContentDetail(adminContentConverter.toType(type), id)));
    }

    @PostMapping("/{type}/{id}/{action}")
    public Result<Void> executeAction(
            @PathVariable @Pattern(regexp = TYPE_PATTERN, message = "内容类型不合法") String type,
            @PathVariable @Positive(message = "内容ID必须大于0") Long id,
            @PathVariable @Pattern(regexp = ACTION_PATTERN, message = "内容动作不合法") String action,
            @Valid @RequestBody(required = false) AdminContentActionRequest request) {
        AdminStpUtil.STP_LOGIC.checkPermission(AdminPermissionPolicy.CONTENT_MANAGE);
        adminContentService.executeAction(adminContentConverter.toActionBO(
                AdminStpUtil.STP_LOGIC.getLoginIdAsLong(), type, id, action, request));
        return Result.ok(null);
    }
}
