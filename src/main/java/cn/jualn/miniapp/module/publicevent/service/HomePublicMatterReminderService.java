package cn.jualn.miniapp.module.publicevent.service;

import cn.jualn.miniapp.module.publicevent.bo.HomePublicMatterRemindersBO;

public interface HomePublicMatterReminderService {

    HomePublicMatterRemindersBO listForUser(long userId);
}
