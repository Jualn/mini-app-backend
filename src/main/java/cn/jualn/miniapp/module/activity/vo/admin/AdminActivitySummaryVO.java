package cn.jualn.miniapp.module.activity.vo.admin;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AdminActivitySummaryVO {

    private Long enrolling;
    private Long ongoing;
    private Long reviewing;
    private Long startingSoon;
    private Integer startingSoonWindowDays;
}
