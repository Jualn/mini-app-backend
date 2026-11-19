package cn.jualn.miniapp.module.admin.query.controller;

import cn.jualn.miniapp.common.web.AdminQuery;
import cn.jualn.miniapp.module.admin.query.converter.AdminDashboardConverter;
import cn.jualn.miniapp.module.admin.query.service.AdminDashboardService;
import cn.jualn.miniapp.module.admin.query.vo.AdminDashboardVO;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class AdminDashboardController {
    private final AdminDashboardService service;
    private final AdminDashboardConverter converter;
    @GetMapping("/v1/admin/dashboard/summary")
    public ResponseEntity<AdminDashboardVO> summary(HttpServletRequest request) {
        if (!request.getParameterMap().isEmpty()) {
            throw AdminQuery.invalid("query");
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(converter.toVO(service.summary()));
    }
}
