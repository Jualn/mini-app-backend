package cn.jualn.miniapp.module.activity.bo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AdminActivitySummaryBO {

    private Long enrolling;
    private Long ongoing;
    private Long reviewing;
    private Long startingSoon;
}
