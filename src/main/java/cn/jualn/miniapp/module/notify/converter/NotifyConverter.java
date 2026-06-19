package cn.jualn.miniapp.module.notify.converter;

import cn.jualn.miniapp.common.enums.NotifyType;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.module.notify.bo.NotificationBO;
import cn.jualn.miniapp.module.notify.bo.NotificationPageBO;
import cn.jualn.miniapp.module.notify.dto.request.NotificationPageQuery;
import cn.jualn.miniapp.module.notify.entity.Notification;
import cn.jualn.miniapp.module.notify.vo.NotificationVO;
import org.mapstruct.Mapper;

import java.util.List;

@Mapper(componentModel = "spring")
public interface NotifyConverter {

	NotificationPageBO toPageBO(NotificationPageQuery query);

	List<NotificationBO> toBOList(List<Notification> entity);

	List<NotificationVO> toVOList(List<NotificationBO> list);

	default TargetType resolveTargetType(Integer targetType) {
		return TargetType.fromCode(targetType);
	}

	default NotifyType resolveNotifyType(Integer notifyType) {
		return NotifyType.fromCode(notifyType);
	}

	default Boolean resolveBoolean(Integer booleanValue) {
		return booleanValue != null && booleanValue.equals(1);
	}
}