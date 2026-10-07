package cn.jualn.miniapp.module.activity.bo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class AdminActivityPageBO {

    private List<AdminActivityListBO> items;
    private Integer page;
    private Integer pageSize;
    private Long totalItems;
}
