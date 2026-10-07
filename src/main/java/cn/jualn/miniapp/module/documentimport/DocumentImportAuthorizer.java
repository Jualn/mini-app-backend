package cn.jualn.miniapp.module.documentimport;

import cn.jualn.miniapp.common.security.AdminStpUtil;
import cn.jualn.miniapp.module.admin.auth.support.AdminPermissionPolicy;
import org.springframework.stereotype.Component;

@Component
public class DocumentImportAuthorizer {
    public void requireEdit(DocumentImportTarget target) {
        AdminStpUtil.STP_LOGIC.checkPermission(target == DocumentImportTarget.ACTIVITY
                ? AdminPermissionPolicy.ACTIVITY_EDIT : AdminPermissionPolicy.PUBLIC_EVENT_EDIT);
    }
}
