package cn.jualn.miniapp.module.content.bo;

import cn.jualn.miniapp.module.content.enums.AdminContentAction;
import cn.jualn.miniapp.module.content.enums.AdminContentType;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AdminContentActionBO {
    private Long operatorId;
    private AdminContentType type;
    private Long contentId;
    private AdminContentAction action;
    private String reason;
}
