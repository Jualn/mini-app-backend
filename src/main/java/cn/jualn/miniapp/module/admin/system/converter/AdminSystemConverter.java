package cn.jualn.miniapp.module.admin.system.converter;

import cn.jualn.miniapp.module.admin.system.bo.AdminSystemOverviewBO;
import cn.jualn.miniapp.module.admin.system.vo.AdminSystemOverviewVO;
import org.springframework.stereotype.Component;

@Component
public class AdminSystemConverter {

    public AdminSystemOverviewVO toOverviewVO(AdminSystemOverviewBO overview) {
        return AdminSystemOverviewVO.builder()
                .environment(overview.getEnvironment())
                .release(overview.getRelease())
                .checkedAt(overview.getCheckedAt())
                .services(overview.getServices().stream().map(this::toServiceVO).toList())
                .build();
    }

    private AdminSystemOverviewVO.ServiceHealthVO toServiceVO(
            AdminSystemOverviewBO.ServiceHealthBO service) {
        return AdminSystemOverviewVO.ServiceHealthVO.builder()
                .key(service.getKey())
                .name(service.getName())
                .description(service.getDescription())
                .status(service.getStatus())
                .statusLabel(service.getStatusLabel())
                .latencyMs(service.getLatencyMs())
                .checkedAt(service.getCheckedAt())
                .build();
    }
}
