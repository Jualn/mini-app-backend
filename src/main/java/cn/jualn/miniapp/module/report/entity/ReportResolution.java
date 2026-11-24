package cn.jualn.miniapp.module.report.entity;
import lombok.Data;
import java.time.LocalDateTime;
@Data
public class ReportResolution {
    private Long id;
    private String targetType;
    private Long targetId;
    private String decision;
    private String contentAction;
    private String outcome;
    private Long handlerId;
    private String remark;
    private LocalDateTime handledAt;
    private String reportIds;
}
