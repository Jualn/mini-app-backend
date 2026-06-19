package cn.jualn.miniapp.module.user.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户公开资料响应。
 */
@Data
@Builder
public class UserPublicProfileVO {

    private Long id;

    private String nickname;

    private String avatarUrl;

    private String backgroundUrl;

    private String bio;

    private Integer gender;

    private LocalDateTime createdAt;
}

