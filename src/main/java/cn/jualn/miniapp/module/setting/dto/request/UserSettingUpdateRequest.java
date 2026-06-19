package cn.jualn.miniapp.module.setting.dto.request;

import lombok.Data;

/**
 * 用户设置更新请求。
 */
@Data
public class UserSettingUpdateRequest {

    private Boolean notifyComment;

    private Boolean notifyReply;

    private Boolean notifyLike;

    private Boolean notifyActivityRemind;

    private Boolean notifyExamRemind;

    private Boolean notifySystem;

    private Boolean notifyAuditResult;

}

