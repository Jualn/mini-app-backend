package cn.jualn.miniapp.module.notify.bo;

import cn.jualn.miniapp.common.enums.NotifyType;
import cn.jualn.miniapp.common.enums.TargetType;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class NotificationBO {

	private Long id;

	private Long userId;

	private NotifyType type;

	private String title;

	private String content;

	private TargetType targetType;

	private Long targetId;

	private Long senderId;

	private Boolean isRead;

	private LocalDateTime createdAt;
}