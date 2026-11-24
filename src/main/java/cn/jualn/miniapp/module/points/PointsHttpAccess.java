package cn.jualn.miniapp.module.points;

import cn.jualn.miniapp.module.admin.auth.service.AdminTokenService;
import cn.dev33.satoken.exception.NotLoginException;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.ResultCode;

/** Authenticate the real admin User without interpreting absence of Points grants as logout. */
public final class PointsHttpAccess {
    private PointsHttpAccess() {}
    public static boolean resource(String path) { return path.startsWith("/v1/admin/points/"); }
    public static long authenticate(AdminTokenService tokens) {
        return authenticate(tokens::requireAuthenticatedSession);
    }
    /** Caller already holds current User/RBAC owner locks and checks current account eligibility. */
    public static long authenticateLocked(AdminTokenService tokens) {
        return authenticate(tokens::requireAuthenticatedSessionIdentity);
    }
    private static long authenticate(java.util.function.LongSupplier session) {
        try { return session.getAsLong(); }
        catch(NotLoginException failure) { throw failure; }
        catch(BusinessException failure) {
            if(failure.getResultCode()==ResultCode.UNAUTHORIZED) throw failure;
            throw unavailable(failure);
        } catch(RuntimeException failure) { throw unavailable(failure); }
    }
    private static RuntimeException unavailable(RuntimeException cause) {
        var failure=PointsProblems.unavailable(PointsProblems.Dependency.AUTHORIZATION);failure.initCause(cause);return failure;
    }
}
