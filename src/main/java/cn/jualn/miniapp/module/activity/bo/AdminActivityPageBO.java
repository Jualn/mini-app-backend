package cn.jualn.miniapp.module.activity.bo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class AdminActivityPageBO {

    private List<AdminActivityListBO> items;
    private AdminActivitySummaryBO summary;
    private Boolean hasMore;
    private String nextCursor;
    private Integer pageSize;
}
