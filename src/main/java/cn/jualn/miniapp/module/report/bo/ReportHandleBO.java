package cn.jualn.miniapp.module.report.bo;

import cn.jualn.miniapp.module.report.enums.ReportStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 举报处理 BO。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReportHandleBO {

    private ReportStatus status;

    private String handleRemark;
}

