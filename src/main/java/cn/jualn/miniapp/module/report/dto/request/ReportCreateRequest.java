package cn.jualn.miniapp.module.report.dto.request;

import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.module.report.enums.ReportReason;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 举报创建请求。
 */
@Data
public class ReportCreateRequest {

    @NotNull(message = "targetType 不能为空")
    private TargetType targetType;

    @NotNull(message = "targetId 不能为空")
    private Long targetId;

    @NotNull(message = "reason 不能为空")
    private ReportReason reason;

    @Size(max = 255, message = "补充说明不能超过255字")
    private String remark;
}

