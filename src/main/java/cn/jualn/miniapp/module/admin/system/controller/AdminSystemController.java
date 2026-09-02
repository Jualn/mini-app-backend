package cn.jualn.miniapp.module.admin.system.controller;

import cn.jualn.miniapp.common.result.Result;
import cn.jualn.miniapp.common.security.AdminStpUtil;
import cn.jualn.miniapp.module.admin.auth.support.AdminPermissionPolicy;
import cn.jualn.miniapp.module.admin.system.converter.AdminSystemConverter;
import cn.jualn.miniapp.module.admin.system.service.AdminSystemService;
import cn.jualn.miniapp.module.admin.system.vo.AdminSystemOverviewVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/admin/system")
public class AdminSystemController {

    private final AdminSystemService adminSystemService;
    private final AdminSystemConverter adminSystemConverter;

    @GetMapping("/overview")
    public Result<AdminSystemOverviewVO> getOverview() {
        AdminStpUtil.STP_LOGIC.checkPermission(AdminPermissionPolicy.SYSTEM_READ);
        return Result.ok(adminSystemConverter.toOverviewVO(adminSystemService.getOverview()));
    }
}
