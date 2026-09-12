package cn.jualn.miniapp.module.exam.bo;
import lombok.Data;
import lombok.Builder;
import java.util.List;
@Data @Builder public class AdminPublicEventPageBO {
    private List<AdminPublicEventListBO> items;
    private Boolean hasMore;
    private String nextCursor;
    private Integer pageSize;
}
