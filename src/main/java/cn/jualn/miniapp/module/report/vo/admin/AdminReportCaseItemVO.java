package cn.jualn.miniapp.module.report.vo.admin;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class AdminReportCaseItemVO {
    private String caseId;
    private String targetId;
    private String targetType;
    private String title;
    private String contentPreview;
    private String authorName;
    private String authorId;
    private Long reportCount;
    private List<ReasonGroup> reasons;
    private String riskLevel;
    private String status;
    private LocalDateTime firstReportedAt;
    private LocalDateTime lastReportedAt;
    private Long waitingMinutes;
    private String previousAuditLabel;

    @Data
    public static class ReasonGroup {
        private String code;
        private String label;
        private Long count;
    }
}
