package cn.jualn.miniapp.module.report.vo;

import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.module.report.enums.ReportReason;
import cn.jualn.miniapp.module.report.enums.ReportStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 举报记录响应。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReportVO {

    private Long id;

    private Long reporterId;

    private TargetType targetType;

    private Long targetId;

    private ReportReason reason;

    private String remark;

    private ReportStatus status;

    private Long handlerId;

    private String handleRemark;

    private LocalDateTime handledAt;

    private LocalDateTime createdAt;
}

