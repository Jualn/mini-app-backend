package cn.jualn.miniapp.module.user.bo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户简版信息 BO（服务内/服务间传输专用）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserSimpleBO {
    /** 用户 ID。 */
    private Long id;

    /** 昵称。 */
    private String nickname;

    /** 头像 URL。 */
    private String avatarUrl;
}
