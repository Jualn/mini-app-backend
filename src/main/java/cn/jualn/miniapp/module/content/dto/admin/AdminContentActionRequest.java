package cn.jualn.miniapp.module.content.dto.admin;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AdminContentActionRequest {

    @Size(max = 255, message = "操作原因不能超过255字")
    private String reason;
}
