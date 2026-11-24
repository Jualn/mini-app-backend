package cn.jualn.miniapp.module.admin.operation.controller;
import cn.jualn.miniapp.module.authorization.web.RbacHttpAccess;
import cn.jualn.miniapp.module.admin.auth.service.AdminTokenService;
import cn.jualn.miniapp.module.admin.operation.service.AdminAuditQueries;
import cn.jualn.miniapp.common.exception.ContractProblemException;
public final class AdminAuditHttpAccess {
    private AdminAuditHttpAccess() {}
    public static boolean resource(String path) {return path.equals("/v1/admin/operation-audits") || path.equals("/v1/admin/authorization/duty-history");}
    public static long authenticate(AdminTokenService tokens) {
        try {return RbacHttpAccess.authenticate(tokens);}
        catch(ContractProblemException failure) {if(failure.getStatus()==org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE)throw AdminAuditQueries.unavailable();throw failure;}
    }
}
