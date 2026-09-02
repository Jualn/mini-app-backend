package cn.jualn.miniapp.module.admin.auth.vo;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class AdminQrConfirmationVO {
    private boolean confirmed;
    private String displayName;
}
