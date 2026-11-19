package cn.jualn.miniapp.module.auth.vo;

import cn.jualn.miniapp.module.user.dto.inner.UserInfoDTO;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class LoginVO {
    private String token;
    private UserInfoDTO userInfo;
}
