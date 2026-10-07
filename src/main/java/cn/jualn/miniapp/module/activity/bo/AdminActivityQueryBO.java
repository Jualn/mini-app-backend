package cn.jualn.miniapp.module.activity.bo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AdminActivityQueryBO {

    private String keyword;
    private Integer publishStatus;
    private Integer lifecycleStatus;
    private String sort;
    private Integer page;
    private Integer pageSize;
}
