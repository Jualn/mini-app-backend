package cn.jualn.miniapp.module.audit.bo;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
public class AdminReviewDetailBO extends AdminReviewItemBO {
    private SafetyReviewProjection.Evidence evidence;
    private cn.jualn.miniapp.module.content.bo.AdminPostProjection.QaContext qaContext;
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private java.util.List<cn.jualn.miniapp.module.media.bo.GrowthMediaBO.File> growthMedia;
    private String content;
    private List<Attachment> attachments;
    private MachineAudit machineAudit;
    private TargetContext targetContext;
    private List<HistoryEntry> history;

    @Data
    public static class Attachment {
        private Long id;
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
        private Long targetId;
        private Integer targetStatus;
        private Long parentId;
    }

    @Data
    public static class HistoryEntry {
        private Long id;
        private String taskId;
        private String submissionVersion;
        private String eventType;
        private String source;
        private String result;
        private String reason;
        private Long operatorId;
        private LocalDateTime createdAt;
    }
}
