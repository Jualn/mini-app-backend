package cn.jualn.miniapp.module.notify.vo.admin;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AdminNotificationPlanSummaryVO {

    private Long pending;
    private Long scheduledToday;
    private Long sentToday;
    private Long systemBroadcasts;
}
