package cn.jualn.miniapp.module.content.bo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class AdminContentPageBO {
    private List<AdminContentListBO> items;
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
