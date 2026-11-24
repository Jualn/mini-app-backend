package cn.jualn.miniapp.module.notify.service;

import cn.jualn.miniapp.module.notify.model.NotificationSubscriptionScene;
import cn.jualn.miniapp.module.notify.enums.NotifyType;
import java.util.List;

/** Business owners evaluate a prospective operation; no relationship or permission writes. */
public interface NotificationSubscriptionContext {
    boolean supports(NotificationSubscriptionScene scene);
    List<NotifyType> types(NotificationSubscriptionScene scene, long resourceId);
}
