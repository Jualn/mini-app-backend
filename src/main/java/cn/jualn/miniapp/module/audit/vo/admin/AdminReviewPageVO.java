package cn.jualn.miniapp.module.audit.vo.admin;

import lombok.Data;

import java.util.List;

@Data
public class AdminReviewPageVO {
    private List<AdminReviewItemVO> items;
    private Long total;
    private Summary summary;
    private Boolean hasMore;
    private String nextCursor;
    private Integer pageSize;

    @Data
    public static class Summary {
        private Long pending;
        private Long highRisk;
        private Long todayCompleted;
        private Long oldestWaitingMinutes;
    }
}
