package cn.jualn.miniapp.module.report.bo;

import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.module.report.enums.ReportReason;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 举报创建 BO。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReportCreateBO {

    private TargetType targetType;

    private Long targetId;

    private ReportReason reason;

    private String remark;
}

