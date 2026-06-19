package cn.jualn.miniapp.module.user.dto.inner;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户简版信息 DTO（服务内/服务间传输专用,像给其他VO拼接用于返回）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserInfoDTO {

    /** 用户 ID。 */
    private Long id;

    /** 昵称。 */
    private String nickname;

    /** 头像 URL。 */
    private String avatarUrl;

    private Integer role;
}