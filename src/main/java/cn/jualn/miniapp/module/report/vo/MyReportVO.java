package cn.jualn.miniapp.module.report.vo;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.module.report.enums.*;
import java.time.LocalDateTime;
/** The reporter projection deliberately has no internal management fields. */
public record MyReportVO(Long id,TargetType targetType,Long targetId,ReportReason reason,String remark,
        ReportStatus status,LocalDateTime createdAt,LocalDateTime handledAt,String outcome) { }
