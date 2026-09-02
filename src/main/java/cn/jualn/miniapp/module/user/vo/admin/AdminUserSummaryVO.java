package cn.jualn.miniapp.module.user.vo.admin;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AdminUserSummaryVO {

    /** 不含已注销用户。 */
    private Long total;
    private Long active;
    private Long muted;
    private Long banned;
    private Long operators;
    private Long deactivated;
}
