package cn.jualn.miniapp.module.report.dto.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AdminReportCaseDecisionRequest {
    @NotBlank(message = "案件结论不能为空")
    @Pattern(regexp = "violation|normal", message = "案件结论不合法")
    private String decision;

    @NotBlank(message = "结案备注不能为空")
    @Size(min = 4, max = 500, message = "结案备注长度必须在4到500个字符之间")
    private String remark;
}
