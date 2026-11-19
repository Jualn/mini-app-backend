package cn.jualn.miniapp.module.person;

import cn.dev33.satoken.exception.NotLoginException;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.module.admin.auth.service.AdminTokenService;

public final class PersonHttpAccess {
    private PersonHttpAccess() {}
    public static long authenticate(AdminTokenService tokens) {
        try { return tokens.requireAuthenticatedSession(); }
        catch(NotLoginException failure) { throw failure; }
        catch(BusinessException failure) { if(failure.getResultCode()==ResultCode.UNAUTHORIZED) throw failure;throw PersonProblems.unavailable(); }
        catch(RuntimeException failure) { throw PersonProblems.unavailable(); }
    }
}
