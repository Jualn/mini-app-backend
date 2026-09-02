package cn.jualn.miniapp.module.admin.system.bo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class AdminSystemOverviewBO {

    private String environment;
    private String release;
    private LocalDateTime checkedAt;
    private List<ServiceHealthBO> services;

    @Data
    @Builder
    public static class ServiceHealthBO {
        private String key;
        private String name;
        private String description;
        private String status;
        private String statusLabel;
        private Long latencyMs;
        private LocalDateTime checkedAt;
    }
}
