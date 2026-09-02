package cn.jualn.miniapp.module.user.mapper;

import lombok.Data;

@Data
public class AdminUserSummaryRow {

    private Long total;
    private Long active;
    private Long muted;
    private Long banned;
    private Long operators;
    private Long deactivated;
}
