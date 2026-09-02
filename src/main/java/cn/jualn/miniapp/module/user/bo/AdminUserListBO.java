package cn.jualn.miniapp.module.user.bo;

import cn.jualn.miniapp.common.enums.UserRole;
import cn.jualn.miniapp.common.enums.UserStatus;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class AdminUserListBO {

    private Long id;
    private String nickname;
    private String avatarUrl;
    private UserRole role;
    private UserStatus status;
    private Boolean deactivated;
    private String restrictionReason;
    private LocalDateTime restrictionExpiresAt;
    private String agreementVersion;
    private LocalDateTime lastLoginAt;
    private LocalDateTime createdAt;
    private LocalDateTime deactivatedAt;
}
