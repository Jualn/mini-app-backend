package cn.jualn.miniapp.module.user.mapper;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AdminUserListRow {

    private Long id;
    private String nickname;
    private String avatarUrl;
    private Integer role;
    private Integer status;
    private String banReason;
    private LocalDateTime banExpireAt;
    private String agreementVersion;
    private LocalDateTime lastLoginAt;
    private LocalDateTime createdAt;
    private LocalDateTime deletedAt;
}
