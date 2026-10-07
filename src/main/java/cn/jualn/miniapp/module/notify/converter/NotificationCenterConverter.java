package cn.jualn.miniapp.module.notify.converter;

import cn.jualn.miniapp.module.notify.bo.NotificationCenterBO;
import cn.jualn.miniapp.module.notify.dto.request.CanonicalNotificationQuery;
import cn.jualn.miniapp.module.notify.vo.NotificationItemVO;
import cn.jualn.miniapp.module.notify.vo.NotificationListVO;
import cn.jualn.miniapp.module.notify.vo.NotificationSummaryVO;
import cn.jualn.miniapp.module.notify.vo.NotificationReadResultVO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface NotificationCenterConverter {
    NotificationCenterBO.Query query(CanonicalNotificationQuery query);
    NotificationItemVO item(NotificationCenterBO.Item item);
    @Mapping(target = "representation", constant = "structured")
    NotificationListVO page(NotificationCenterBO.Page page);
    NotificationSummaryVO summary(NotificationCenterBO.Summary summary);
    NotificationReadResultVO read(NotificationCenterBO.ReadResult read);
}
