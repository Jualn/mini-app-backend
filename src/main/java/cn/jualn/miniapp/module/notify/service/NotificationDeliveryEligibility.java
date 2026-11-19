package cn.jualn.miniapp.module.notify.service;

import cn.jualn.miniapp.module.notify.bo.NotificationDeliveryBO;
import java.util.Map;

/** Owner implementation must leave unrelated notification types eligible. */
public interface NotificationDeliveryEligibility {
    boolean isEligible(NotificationDeliveryBO notification, Map<String,Object> frozen);
}
