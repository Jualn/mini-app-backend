package cn.jualn.miniapp.module.notify.bo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class AdminNotificationPlanPageBO {

    private List<AdminNotificationPlanItemBO> items;
    private AdminNotificationPlanSummaryBO summary;
    private Boolean hasMore;
    private String nextCursor;
    private Integer pageSize;
}
