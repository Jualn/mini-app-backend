package cn.jualn.miniapp.module.admin.auth.vo;

import cn.jualn.miniapp.module.admin.auth.enums.AdminQrLoginStatus;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class AdminQrSessionPollVO {
    private AdminQrLoginStatus status;
    private String message;
    private String token;
    private AdminIdentityVO profile;
}
