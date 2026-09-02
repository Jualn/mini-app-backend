package cn.jualn.miniapp.module.activity.mapper;

import lombok.Data;

@Data
public class AdminActivitySummaryRow {

    private Long enrolling;
    private Long ongoing;
    private Long reviewing;
    private Long startingSoon;
}
