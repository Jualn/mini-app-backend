package cn.jualn.miniapp.module.report.bo;

import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.module.report.enums.ReportStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 举报分页 BO。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReportPageBO {

    private Long reporterId;

    private TargetType targetType;

    private Long targetId;

    private ReportStatus status;

    private Long lastId;

    private Integer pageSize;
}

