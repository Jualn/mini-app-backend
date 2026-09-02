package cn.jualn.miniapp.module.admin.system.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class AdminSystemOverviewVO {

    private String environment;
    private String release;
    private LocalDateTime checkedAt;
    private List<ServiceHealthVO> services;

    @Data
    @Builder
    public static class ServiceHealthVO {
        private String key;
        private String name;
        private String description;
        private String status;
        private String statusLabel;
        private Long latencyMs;
        private LocalDateTime checkedAt;
    }
}
