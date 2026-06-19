package cn.jualn.miniapp.module.notify.dto.request;

import cn.jualn.miniapp.common.enums.NotifyType;
import lombok.Data;

@Data
public class NotificationPageQuery {

    private Long lastId;

    private Integer pageSize;

    private NotifyType type;

    private Integer isRead;
}