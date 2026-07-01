package cn.jualn.miniapp.module.user.bo;

import cn.jualn.miniapp.common.enums.UserRole;
import cn.jualn.miniapp.common.enums.UserStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserProfileBO {

    private String nickname;

    private String avatarUrl;

    private String backgroundUrl;

    private String bio;

    private Integer gender;

    private UserRole role;

    private UserStatus status;

    private LocalDateTime lastLoginAt;

    private LocalDateTime createdAt;
}
