package cn.jualn.miniapp.module.report.vo.admin;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class AdminReportCasePageVO {
    private List<AdminReportCaseItemVO> items;
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
