package cn.jualn.miniapp.module.user.bo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AdminUserSummaryBO {

    private Long total;
    private Long active;
    private Long muted;
    private Long banned;
    private Long operators;
    private Long deactivated;
}
