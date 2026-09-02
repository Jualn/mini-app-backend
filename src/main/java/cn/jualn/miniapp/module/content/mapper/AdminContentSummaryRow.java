package cn.jualn.miniapp.module.content.mapper;

import lombok.Data;

@Data
public class AdminContentSummaryRow {
    private Long published;
    private Long pending;
    private Long reported;
    private Long removedToday;
}
