package cn.jualn.miniapp.module.notify.service;

import cn.jualn.miniapp.module.notify.bo.AdminNotificationPlanItemBO;
import cn.jualn.miniapp.module.notify.bo.AdminNotificationPlanPageBO;
import cn.jualn.miniapp.module.notify.bo.AdminNotificationPlanQueryBO;

public interface AdminNotificationPlanService {

    AdminNotificationPlanPageBO pagePlans(AdminNotificationPlanQueryBO query);

    AdminNotificationPlanItemBO getPlan(Long planId);
}
