package cn.jualn.miniapp.module.setting.bo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserSettingBO {

    private Boolean notifyComment;

    private Boolean notifyReply;

    private Boolean notifyLike;

    private Boolean notifyActivityRemind;

    private Boolean notifyExamRemind;

    private Boolean notifySystem;

    private Boolean notifyAuditResult;
}
