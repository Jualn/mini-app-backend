package cn.jualn.miniapp.module.activity.dto.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AdminActivityReasonRequest {

    @NotBlank(message = "操作原因不能为空")
    @Size(max = 255, message = "操作原因不能超过255字")
    private String reason;
}
