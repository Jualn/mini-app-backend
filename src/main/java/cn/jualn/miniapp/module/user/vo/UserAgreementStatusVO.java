package cn.jualn.miniapp.module.user.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户协议状态响应。
 */
@Data
@Builder
public class UserAgreementStatusVO {

    private Boolean agreed;

    private String version;

    private LocalDateTime agreedAt;
}

