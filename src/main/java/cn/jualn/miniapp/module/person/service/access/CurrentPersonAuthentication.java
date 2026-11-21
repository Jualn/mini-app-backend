package cn.jualn.miniapp.module.person.service.access;


import java.time.LocalDateTime;

import cn.dev33.satoken.exception.NotLoginException;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.module.admin.auth.service.AdminTokenService;
import cn.jualn.miniapp.module.authorization.service.TransactionalAuthorization;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import static cn.jualn.miniapp.module.person.PersonProblems.denied;
import static cn.jualn.miniapp.module.person.PersonProblems.unavailable;

@Service
@RequiredArgsConstructor
public class CurrentPersonAuthentication implements PersonAuthentication {
    private final AdminTokenService tokens;
    private final TransactionalAuthorization authorization;

    public long actor() {
        return session(false);
    }

    private long session(boolean locked) {
        try {
            return locked ? tokens.requireAuthenticatedSessionIdentity() : tokens.requireAuthenticatedSession();
        } catch (NotLoginException failure) {
            throw failure;
        } catch (BusinessException failure) {
            if (failure.getResultCode() == ResultCode.UNAUTHORIZED) throw failure;
            throw unavailable();
        } catch (RuntimeException failure) {
            throw unavailable();
        }
    }

    @Override public void verifyActor(long actor) {
        if (session(true) != actor) throw denied();
    }

    public void require(long actor, String namespace, String permission, LocalDateTime at) {
        try {
            // Revalidate real session at the final write boundary as well.
            // The caller already holds current User/RBAC locks. Avoid a nested User normalization transaction.
            if (session(true) != actor) throw denied();
            var current = authorization.currentNow(actor);
            boolean allowed = "MENTOR".equals(namespace) && "mentor:qualification-manage".equals(permission)
                    ? current.allowsWork("MENTOR_MANAGE", namespace, permission) : current.allows(permission, namespace);
            if (!allowed) throw denied();
        } catch (cn.jualn.miniapp.common.exception.ContractProblemException failure) {
            if (failure.getStatus() == org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE) throw unavailable();
            throw failure;
        }
    }
}
