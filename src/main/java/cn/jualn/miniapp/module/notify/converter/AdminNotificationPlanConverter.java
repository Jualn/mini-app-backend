package cn.jualn.miniapp.module.notify.converter;

import cn.jualn.miniapp.module.notify.bo.AdminNotificationPlanItemBO;
import cn.jualn.miniapp.module.notify.bo.AdminNotificationPlanPageBO;
import cn.jualn.miniapp.module.notify.bo.AdminNotificationPlanQueryBO;
import cn.jualn.miniapp.module.notify.dto.admin.AdminNotificationPlanPageQuery;
import cn.jualn.miniapp.module.notify.vo.admin.AdminNotificationPlanItemVO;
import cn.jualn.miniapp.module.notify.vo.admin.AdminNotificationPlanPageVO;
import cn.jualn.miniapp.module.notify.vo.admin.AdminNotificationPlanSummaryVO;
import org.springframework.stereotype.Component;

@Component
public class AdminNotificationPlanConverter {

    public AdminNotificationPlanQueryBO toQueryBO(AdminNotificationPlanPageQuery query) {
        return AdminNotificationPlanQueryBO.builder()
                .keyword(trimToNull(query.getKeyword()))
                .status(toStatus(query.getStatus()))
                .sourceType(toSourceType(query.getSourceType()))
                .scope(toScope(query.getScope()))
                .cursor(query.getCursor())
                .pageSize(query.getPageSize())
                .build();
    }

    public AdminNotificationPlanPageVO toPageVO(AdminNotificationPlanPageBO page) {
        return AdminNotificationPlanPageVO.builder()
                .items(page.getItems().stream().map(this::toItemVO).toList())
                .summary(AdminNotificationPlanSummaryVO.builder()
                        .pending(page.getSummary().getPending())
                        .scheduledToday(page.getSummary().getScheduledToday())
                        .sentToday(page.getSummary().getSentToday())
                        .systemBroadcasts(page.getSummary().getSystemBroadcasts())
                        .build())
                .hasMore(page.getHasMore())
                .nextCursor(page.getNextCursor())
                .pageSize(page.getPageSize())
                .build();
    }

    public AdminNotificationPlanItemVO toItemVO(AdminNotificationPlanItemBO item) {
        return AdminNotificationPlanItemVO.builder()
                .id(item.getId().toString())
                .sourceType(item.getSourceType())
                .sourceId(item.getSourceId() == null ? null : item.getSourceId().toString())
                .notifyType(item.getNotifyType())
                .title(item.getTitle())
                .content(item.getContent())
                .scope(item.getScope())
                .scene(item.getScene())
                .sendAt(item.getSendAt())
                .status(item.getStatus())
                .createdBy(item.getCreatedBy() == null ? null : item.getCreatedBy().toString())
                .createdAt(item.getCreatedAt())
                .build();
    }

    private Integer toStatus(String value) {
        if (value == null) return null;
        return switch (value) {
            case "pending" -> 0;
            case "sent" -> 1;
            case "cancelled" -> 2;
            default -> null;
        };
    }

    private Integer toSourceType(String value) {
        if (value == null) return null;
        return switch (value) {
            case "activity" -> 1;
            case "exam" -> 2;
            case "system" -> 3;
            default -> null;
        };
    }

    private Integer toScope(String value) {
        if (value == null) return null;
        return "all".equals(value) ? 1 : 0;
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
