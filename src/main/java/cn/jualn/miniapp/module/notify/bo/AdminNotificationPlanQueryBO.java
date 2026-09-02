package cn.jualn.miniapp.module.notify.bo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AdminNotificationPlanQueryBO {

    private String keyword;
    private Integer status;
    private Integer sourceType;
    private Integer scope;
    private String cursor;
    private Integer pageSize;
}
