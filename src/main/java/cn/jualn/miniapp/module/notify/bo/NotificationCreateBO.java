package cn.jualn.miniapp.module.notify.bo;

import cn.jualn.miniapp.common.enums.NotifyType;
import cn.jualn.miniapp.common.enums.TargetType;
import lombok.Data;

@Data
public class NotificationCreateBO {

	private Long userId;

	private NotifyType type;

	private String title;

	private String content;

	private TargetType targetType;

	private Long targetId;

	private Long senderId;
}