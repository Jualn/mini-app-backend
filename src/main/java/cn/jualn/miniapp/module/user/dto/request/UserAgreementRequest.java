package cn.jualn.miniapp.module.user.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 用户协议同意请求。
 */
@Data
public class UserAgreementRequest {

    @NotBlank(message = "协议版本不能为空")
    private String version;
}

