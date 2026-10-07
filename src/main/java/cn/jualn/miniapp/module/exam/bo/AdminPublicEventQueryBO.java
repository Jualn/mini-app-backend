package cn.jualn.miniapp.module.exam.bo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AdminPublicEventQueryBO {
    private String keyword;
    private Integer publishStatus;
    private Integer lifecycleStatus;
    private String sort;
    private Integer page;
    private Integer pageSize;
}
