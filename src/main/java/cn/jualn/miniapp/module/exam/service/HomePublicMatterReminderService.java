package cn.jualn.miniapp.module.exam.service;

import cn.jualn.miniapp.module.exam.bo.HomePublicMatterRemindersBO;

public interface HomePublicMatterReminderService {

    HomePublicMatterRemindersBO listForUser(long userId);
}
