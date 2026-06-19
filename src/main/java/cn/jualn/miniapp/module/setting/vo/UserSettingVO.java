package cn.jualn.miniapp.module.setting.vo;

import lombok.*;

/**
 * 用户设置响应。
 */
@Data
@Builder
public class UserSettingVO {

    private Boolean notifyComment;

    private Boolean notifyReply;

    private Boolean notifyLike;

    private Boolean notifyActivityRemind;

    private Boolean notifyExamRemind;

    private Boolean notifySystem;

    private Boolean notifyAuditResult;

}

