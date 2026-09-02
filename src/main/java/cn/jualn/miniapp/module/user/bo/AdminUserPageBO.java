package cn.jualn.miniapp.module.user.bo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class AdminUserPageBO {

    private List<AdminUserListBO> items;
    private AdminUserSummaryBO summary;
    private Boolean hasMore;
    private String nextCursor;
    private Integer pageSize;
}
