package cn.jualn.miniapp.module.audit.entity;
import lombok.Data;
@Data
public class ContentSafetyCheck {
    private Long auditLogId;
    private Long taskId;
    private String inputReference;
    private String executionState;
    private String result;
    private String providerFacts;
    private Long supersedesCheckId;
    private String wxTraceId;
}
