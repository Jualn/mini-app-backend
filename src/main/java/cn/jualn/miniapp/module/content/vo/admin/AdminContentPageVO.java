package cn.jualn.miniapp.module.content.vo.admin;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class AdminContentPageVO {
    private List<AdminContentListVO> items;
    private Summary summary;
    private Boolean hasMore;
    private String nextCursor;
    private Integer pageSize;

    @Data
    @Builder
    public static class Summary {
        private Long published;
        private Long pending;
        private Long reported;
        private Long removedToday;
    }
}
