package cn.jualn.miniapp.module.content.service;

import cn.jualn.miniapp.module.content.bo.AdminContentActionBO;
import cn.jualn.miniapp.module.content.bo.AdminContentDetailBO;
import cn.jualn.miniapp.module.content.bo.AdminContentPageBO;
import cn.jualn.miniapp.module.content.bo.AdminContentQueryBO;
import cn.jualn.miniapp.module.content.enums.AdminContentType;

public interface AdminContentService {

    AdminContentPageBO pageContents(AdminContentQueryBO query);

    AdminContentDetailBO getContentDetail(AdminContentType type, Long contentId);

    void executeAction(AdminContentActionBO command);
}
