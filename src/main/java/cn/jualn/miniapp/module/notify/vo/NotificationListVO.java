package cn.jualn.miniapp.module.notify.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record NotificationListVO(List<NotificationItemVO> items,
                                 String nextCursor, boolean hasMore, String headCursor) { }
