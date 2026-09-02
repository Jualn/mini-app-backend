package cn.jualn.miniapp.module.user.mapper;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class UserAuthRow {

    private Long id;
    private Integer role;
    private Integer status;
    private String banReason;
    private LocalDateTime banExpireAt;
    private LocalDateTime lastLoginAt;
}
