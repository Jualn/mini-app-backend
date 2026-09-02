package cn.jualn.miniapp.module.user.dto.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AdminUserRoleUpdateRequest {

    @NotBlank(message = "目标角色不能为空")
    @Pattern(regexp = "user|operator|admin", message = "目标角色不合法")
    private String role;

    @NotBlank(message = "角色调整原因不能为空")
    @Size(max = 255, message = "角色调整原因不能超过255字")
    private String reason;
}
