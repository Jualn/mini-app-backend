package cn.jualn.miniapp.module.notify.bo;

import cn.jualn.miniapp.common.enums.NotifyType;
import lombok.Data;

@Data
public class NotificationPageBO {

	private Long lastId;

	private Integer pageSize;

	private NotifyType type;

	private Integer isRead;
}