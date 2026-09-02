package cn.jualn.miniapp.module.user.bo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AdminUserRestrictionBO {

    private Long operatorId;
    private Long targetUserId;
    private String reason;
    private Integer durationDays;
}
