package cn.jualn.miniapp.module.report.mapper;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AdminReportEvidenceRow {
    private Long reportId;
    private Long reporterId;
    private String reporterName;
    private String reason;
    private String remark;
    private LocalDateTime createdAt;
}
