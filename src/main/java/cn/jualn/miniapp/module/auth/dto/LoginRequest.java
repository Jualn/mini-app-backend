package cn.jualn.miniapp.module.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;

@Getter
public class LoginRequest {

    @NotBlank(message = "code 不能为空")
    private String code;
}
