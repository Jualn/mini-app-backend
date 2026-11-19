package cn.jualn.miniapp.module.documentimport;

import cn.jualn.miniapp.common.security.AdminStpUtil;
import cn.jualn.miniapp.module.admin.auth.support.AdminPermissionPolicy;
import org.springframework.stereotype.Component;

@Component
@lombok.RequiredArgsConstructor
public class DocumentImportAuthorizer {
    private final cn.jualn.miniapp.module.authorization.service.CurrentAuthorization authorization;
    public long requireEdit(DocumentImportTarget target) {
        long actor=AdminStpUtil.STP_LOGIC.getLoginIdAsLong();requireEdit(actor,target);return actor;
    }
    public void requireEdit(long actor,DocumentImportTarget target) {
        if(target==null) throw new cn.jualn.miniapp.common.exception.BusinessException(cn.jualn.miniapp.common.result.ResultCode.FORBIDDEN);
        boolean activity=target==DocumentImportTarget.ACTIVITY;
        authorization.require(actor,activity?"activity:edit":"public-event:edit",activity?"ACTIVITY":"PUBLIC_EVENT",activity?"activity:edit":"public-event:edit");
    }
}
