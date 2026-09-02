package cn.jualn.miniapp.module.audit.vo.admin;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
public class AdminReviewDetailVO extends AdminReviewItemVO {
    private String content;
    private List<Attachment> attachments;
    private MachineAudit machineAudit;
    private TargetContext targetContext;
    private List<HistoryEntry> history;

    @Data
    public static class Attachment {
        private String id;
        private String type;
        private String name;
        private String url;
    }

    @Data
    public static class MachineAudit {
        private String traceId;
        private String provider;
        private String suggest;
        private Integer riskScore;
        private List<String> matchedLabels;
    }

    @Data
    public static class TargetContext {
        private String targetType;
        private String targetId;
        private Integer targetStatus;
        private String parentId;
    }

    @Data
    public static class HistoryEntry {
        private String id;
        private String eventType;
        private String source;
        private String result;
        private String reason;
        private String operatorId;
        private LocalDateTime createdAt;
    }
}
