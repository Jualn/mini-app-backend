package cn.jualn.miniapp.module.audit.dto.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AdminReviewDecisionRequest {
    @NotBlank(message = "人工结论不能为空")
    @Pattern(regexp = "approve|reject", message = "人工结论不合法")
    private String action;

    @Pattern(regexp = "illegal|abuse|advertising|false-info|other", message = "拒绝原因代码不合法")
    private String reasonCode;

    @Size(max = 200, message = "备注不能超过200个字符")
    private String remark;
}
