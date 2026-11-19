package cn.jualn.miniapp.module.audit.entity;
import lombok.Data;
import java.time.LocalDateTime;
/** One immutable content submission; mutable lifecycle is serialized on this row. */
@Data
public class ContentSafetyReview {
    private Long taskId;
    private Integer targetType;
    private Long targetId;
    private String submissionVersion;
    private String scene;
    private String resourceNamespace;
    private Long businessRevision;
    private String snapshotTitle;
    private String snapshotContent;
    private String snapshotAttachments;
    private String snapshotQaRelation;
    private String growthOrganizationId;
    private Integer expectedChecks;
    private String state;
    private Long previousTaskId;
    private Long supersededByTaskId;
    private String invalidationReason;
    private String decisionOrigin;
    private LocalDateTime decidedAt;
    private LocalDateTime createdAt;
}
