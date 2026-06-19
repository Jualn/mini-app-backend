package cn.jualn.miniapp.module.user.bo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserPublicProfileBO {
    private Long id;

    private String nickname;

    private String avatarUrl;

    private String backgroundUrl;

    private String bio;

    private Integer gender;

    private LocalDateTime createdAt;
}
