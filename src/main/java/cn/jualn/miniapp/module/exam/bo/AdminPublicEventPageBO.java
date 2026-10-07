package cn.jualn.miniapp.module.exam.bo;

import java.util.List;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AdminPublicEventPageBO {
    private List<AdminPublicEventListBO> items;
    private Integer page;
    private Integer pageSize;
    private Long totalItems;
}
