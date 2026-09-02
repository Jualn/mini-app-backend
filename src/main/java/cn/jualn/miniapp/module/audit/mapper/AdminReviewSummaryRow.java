package cn.jualn.miniapp.module.audit.mapper;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AdminReviewSummaryRow {
    private Long pending;
    private Long todayCompleted;
    private LocalDateTime oldestPendingAt;
}
