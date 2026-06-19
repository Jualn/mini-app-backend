package cn.jualn.miniapp.module.report.dto.request;

import cn.jualn.miniapp.module.report.enums.ReportStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 举报处理请求。
 */
@Data
public class ReportHandleRequest {

    @NotNull(message = "status 不能为空")
    private ReportStatus status;

    @Size(max = 255, message = "处理备注不能超过255字")
    private String handleRemark;
}

