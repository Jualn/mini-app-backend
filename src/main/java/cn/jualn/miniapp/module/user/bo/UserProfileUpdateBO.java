package cn.jualn.miniapp.module.user.bo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserProfileUpdateBO {

    private String nickname;

    private String avatarUrl;

    private String backgroundUrl;

    private String bio;

    private Integer gender;
}
