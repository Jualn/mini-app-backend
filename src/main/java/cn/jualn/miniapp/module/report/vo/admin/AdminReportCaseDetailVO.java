package cn.jualn.miniapp.module.report.vo.admin;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
public class AdminReportCaseDetailVO extends AdminReportCaseItemVO {
    private String content;
    private Author author;
    private List<Evidence> evidence;
    private List<ContextItem> context;
    private List<AuditItem> relatedAudit;

    @Data
    public static class Author {
        private String nickname;
        private String avatarText;
        private String statusLabel;
        private LocalDateTime joinedAt;
        private Long publishedCount;
        private Long rejectedCount;
    }

    @Data
    public static class Evidence {
        private String reportId;
        private String reporterName;
        private String reporterId;
        private String reason;
        private String remark;
        private LocalDateTime createdAt;
    }

    @Data
    public static class ContextItem {
        private String label;
        private String value;
    }

    @Data
    public static class AuditItem {
        private String title;
        private String detail;
        private LocalDateTime createdAt;
        private String tone;
    }
}
