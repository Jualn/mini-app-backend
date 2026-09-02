package cn.jualn.miniapp.module.user.dto.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AdminUserRestrictionRequest {

    @NotBlank(message = "处置原因不能为空")
    @Size(max = 255, message = "处置原因不能超过255字")
    private String reason;

    /**
     * 限制天数；不传表示永久。恢复操作会忽略该字段。
     */
    @Positive(message = "限制天数必须大于0")
    private Integer durationDays;
}
