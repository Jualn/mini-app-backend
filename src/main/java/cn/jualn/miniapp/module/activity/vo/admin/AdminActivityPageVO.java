package cn.jualn.miniapp.module.activity.vo.admin;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class AdminActivityPageVO {

    private List<AdminActivityListVO> items;
    private AdminActivitySummaryVO summary;
    private Boolean hasMore;
    private String nextCursor;
    private Integer pageSize;
}
