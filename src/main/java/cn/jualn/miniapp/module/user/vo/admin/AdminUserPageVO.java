package cn.jualn.miniapp.module.user.vo.admin;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class AdminUserPageVO {

    private List<AdminUserListVO> items;
    private AdminUserSummaryVO summary;
    private Boolean hasMore;
    private String nextCursor;
    private Integer pageSize;
}
