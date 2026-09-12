package cn.jualn.miniapp.module.exam.vo.admin;
import lombok.Data;
import lombok.Builder;
import java.util.List;
@Data @Builder public class AdminPublicEventPageVO {
    private List<AdminPublicEventListVO> items;
    private Boolean hasMore;
    private String nextCursor;
    private Integer pageSize;
}
