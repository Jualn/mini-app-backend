package cn.jualn.miniapp.module.user.bo;

import cn.jualn.miniapp.common.enums.UserRole;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AdminUserRoleChangeBO {

    private Long operatorId;
    private Long targetUserId;
    private UserRole targetRole;
    private String reason;
}
