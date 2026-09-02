package cn.jualn.miniapp.module.user.bo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AdminUserQueryBO {

    private String keyword;
    private Integer status;
    private Boolean deactivated;
    private Integer role;
    private String sort;
    private String cursor;
    private Integer pageSize;
}
