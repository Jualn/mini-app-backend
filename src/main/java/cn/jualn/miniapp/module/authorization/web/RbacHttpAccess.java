package cn.jualn.miniapp.module.authorization.web;

import cn.jualn.miniapp.common.exception.AdminAccountRestrictedException;
import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.module.authorization.support.RbacProblems;
import cn.dev33.satoken.exception.NotLoginException;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.module.admin.auth.service.AdminTokenService;
import org.springframework.http.HttpStatus;

/**
 * Identity-only entry for the nine canonical operations. No grant is required to diagnose oneself.
 */
public final class RbacHttpAccess {
    private RbacHttpAccess() {
    }

    public static boolean resource(String path) {
        return path.startsWith("/v1/admin/authorization/");
    }

    public static long authenticate(AdminTokenService tokens) {
        try {
            return tokens.requireAuthenticatedSession();
        } catch (NotLoginException e) {
            throw RbacProblems.problem(HttpStatus.UNAUTHORIZED, "admin-authentication-required");
        } catch (AdminAccountRestrictedException e) {
            throw ContractProblemException.restrictedAdminAuthentication();
        } catch (BusinessException e) {
            if (e.getResultCode() == ResultCode.UNAUTHORIZED)
                throw RbacProblems.problem(HttpStatus.UNAUTHORIZED, "admin-authentication-required");
            throw RbacProblems.unavailable();
        } catch (RuntimeException e) {
            throw RbacProblems.unavailable();
        }
    }
}
