package cn.jualn.miniapp.module.report.mapper;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AdminReportCaseRow {
    private Long latestReportId;
    private String targetType;
    private Long targetId;
    private String title;
    private String contentPreview;
    private Long authorId;
    private String authorName;
    private Long reportCount;
    private Long illegalCount;
    private Long pornCount;
    private Long advertisingCount;
    private Long falseInfoCount;
    private Long otherCount;
    private String status;
    private LocalDateTime firstReportedAt;
    private LocalDateTime lastReportedAt;
    private Long handlerId;
    private String handleRemark;
    private LocalDateTime handledAt;
}
