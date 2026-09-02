package cn.jualn.miniapp.module.report.bo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class AdminReportCasePageBO {
    private List<AdminReportCaseItemBO> items;
    private Summary summary;
    private Boolean hasMore;
    private String nextCursor;
    private Integer pageSize;

    @Data
    @Builder
    public static class Summary {
        private Long pendingCases;
        private Long urgentCases;
        private Long reportsToday;
        private Long completedToday;
    }
}
