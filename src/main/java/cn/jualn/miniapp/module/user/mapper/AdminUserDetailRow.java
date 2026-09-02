package cn.jualn.miniapp.module.user.mapper;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AdminUserDetailRow {

    private Long id;
    private String openid;
    private String unionid;
    private String nickname;
    private String avatarUrl;
    private String bio;
    private Integer gender;
    private Integer role;
    private Integer status;
    private String banReason;
    private LocalDateTime banExpireAt;
    private String agreementVersion;
    private LocalDateTime agreementAgreedAt;
    private LocalDateTime lastLoginAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime deletedAt;
}
