package cn.jualn.miniapp.module.user.vo.admin;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class AdminUserDetailVO {

    private String id;
    private String nickname;
    private String avatarUrl;
    private String bio;
    private String gender;
    private String openidMasked;
    private Boolean unionidLinked;
    private String role;
    private String status;
    private String restrictionReason;
    private LocalDateTime restrictionExpiresAt;
    private Agreement agreement;
    private LocalDateTime lastLoginAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime deactivatedAt;

    @Data
    @Builder
    public static class Agreement {
        private String version;
        private LocalDateTime agreedAt;
    }
}
