package cn.jualn.miniapp.module.audit.bo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class AdminReviewPageBO {
    private List<AdminReviewItemBO> items;
    private Long total;
    private Summary summary;
    private Boolean hasMore;
    private String nextCursor;
    private Integer pageSize;

    @Data
    @Builder
    public static class Summary {
        private Long pending;
        private Long highRisk;
        private Long todayCompleted;
        private Long oldestWaitingMinutes;
    }
}
