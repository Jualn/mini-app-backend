package cn.jualn.miniapp.module.activity.vo.admin;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class AdminActivityPageVO {

    private List<AdminActivityListVO> items;
    private Integer page;
    private Integer pageSize;
    private Long totalItems;
}
