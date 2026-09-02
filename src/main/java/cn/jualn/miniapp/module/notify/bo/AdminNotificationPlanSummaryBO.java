package cn.jualn.miniapp.module.notify.bo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AdminNotificationPlanSummaryBO {

    private Long pending;
    private Long scheduledToday;
    private Long sentToday;
    private Long systemBroadcasts;
}
