package cn.jualn.miniapp.module.user.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户资料响应。
 */
@Data
@Builder
public class UserProfileVO {

    private String nickname;

    private String avatarUrl;

    private String backgroundUrl;

    private String bio;

    private Integer gender;

    private Integer role;

    private String roleDesc;

    private Integer status;

    private String statusDesc;

    private Boolean banned;

    private Boolean muted;

    private String banReason;

    private LocalDateTime banExpireAt;

    private List<String> capabilities;

    private LocalDateTime createdAt;
}

