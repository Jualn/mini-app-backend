package cn.jualn.miniapp.module.user.bo;

import cn.jualn.miniapp.common.enums.UserRole;
import cn.jualn.miniapp.common.enums.UserStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 用户权限与状态信息 BO（服务内/服务间传输专用）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserAuthBO {

    private Long id;
    private UserRole role;
    private UserStatus status;
    private String banReason;
    private LocalDateTime banExpireAt;
    private LocalDateTime lastLoginAt;
}
