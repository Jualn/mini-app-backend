package cn.jualn.miniapp.module.notify.service;

import cn.jualn.miniapp.module.notify.bo.NotificationCenterBO;
import java.util.List;

public interface NotificationCenterService {
    NotificationCenterBO.Page list(NotificationCenterBO.Query query);
    NotificationCenterBO.Summary summary(String afterCursor);
    NotificationCenterBO.Item get(String notificationId);
    NotificationCenterBO.ReadResult batchRead(List<String> notificationIds);
    NotificationCenterBO.ReadResult readThrough(String throughCursor);
}
