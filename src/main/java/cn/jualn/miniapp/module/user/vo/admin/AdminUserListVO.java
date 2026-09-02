package cn.jualn.miniapp.module.user.vo.admin;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class AdminUserListVO {

    private String id;
    private String nickname;
    private String avatarUrl;
    private String role;
    private String status;
    private String restrictionReason;
    private LocalDateTime restrictionExpiresAt;
    private String agreementVersion;
    private LocalDateTime lastLoginAt;
    private LocalDateTime createdAt;
    private LocalDateTime deactivatedAt;
}
