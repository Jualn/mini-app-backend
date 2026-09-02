package cn.jualn.miniapp.module.notify.vo.admin;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class AdminNotificationPlanPageVO {

    private List<AdminNotificationPlanItemVO> items;
    private AdminNotificationPlanSummaryVO summary;
    private Boolean hasMore;
    private String nextCursor;
    private Integer pageSize;
}
