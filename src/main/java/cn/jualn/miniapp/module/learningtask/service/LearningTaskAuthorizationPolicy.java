package cn.jualn.miniapp.module.learningtask.service;

import cn.dev33.satoken.stp.StpUtil;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.common.security.AdminStpUtil;
import cn.jualn.miniapp.module.authorization.service.CurrentAuthorization;
import cn.jualn.miniapp.module.authorization.service.RbacCatalog;
import cn.jualn.miniapp.module.authorization.model.RbacModels.Source;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Action;
import cn.jualn.miniapp.module.user.service.UserAccountService;

import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import cn.jualn.miniapp.module.authorization.model.RbacModels.Decision;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static cn.jualn.miniapp.module.learningtask.LearningTaskProblems.Dependency;
import static cn.jualn.miniapp.module.learningtask.LearningTaskProblems.missing;
import static cn.jualn.miniapp.module.learningtask.LearningTaskProblems.unavailable;

/**
 * LearningTask owning policy. GLOBAL is confined to this namespace; creator is not a grant.
 */
@Service
public class LearningTaskAuthorizationPolicy {
    private static final String NAMESPACE = "LEARNING_TASK";
    private final CurrentAuthorization authorization;
    private final RbacCatalog catalog;
    private final UserAccountService users;
    private final LearningTaskScopePolicy scopes;

    public LearningTaskAuthorizationPolicy(CurrentAuthorization authorization, RbacCatalog catalog, UserAccountService users, LearningTaskScopePolicy scopes) {
        this.authorization = authorization;
        this.catalog = catalog;
        this.users = users;
        this.scopes = scopes;
    }

    public long authorize(Action action, Long owner) {
        return authorize(action, null, owner);
    }

    public long authorize(Action action, Long task, Long owner) {
        if (action == Action.MY_READ || action == Action.CONFIRM || action == Action.SUBMIT) {
            StpUtil.checkLogin();
            long actor = StpUtil.getLoginIdAsLong();
            if (TransactionSynchronizationManager.isActualTransactionActive())
                scopes.begin(actor);
            else users.assertLoginAllowed(actor);
            if (owner != null && owner.longValue() != actor) {
                throw missing();
            }
            return actor;
        }
        AdminStpUtil.STP_LOGIC.checkLogin();
        long actor = AdminStpUtil.STP_LOGIC.getLoginIdAsLong();
        requireAdmin(actor, action);
        if (TransactionSynchronizationManager.isActualTransactionActive())
            scopes.task(actor, action, task);
        return actor;
    }

    /**
     * Fresh primary-DB decision on every invocation, including idempotent replay.
     */
    public void requireAdmin(long actor, Action action) {
        Set<String> codes = permissions(action);
        Decision decision;
        try {
            decision = TransactionSynchronizationManager.isActualTransactionActive() ? scopes.decision(actor) : authorization.current(actor);
        } catch (ContractProblemException failure) {
            if (failure.getStatus() != HttpStatus.SERVICE_UNAVAILABLE) {
                throw failure;
            }
            var problem = unavailable(Dependency.AUTHORIZATION);
            problem.initCause(failure);
            throw problem;
        }
        if (decision.userId() != actor || !decision.accountEligible() || decision.source() != Source.ASSIGNMENT) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
        if (!catalog.supports(NAMESPACE, codes)) {
            throw unavailable(Dependency.AUTHORIZATION);
        }
        if (!codes.stream().allMatch(code -> decision.allows(code, NAMESPACE))) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
    }

    public static Set<String> permissions(Action action) {
        return switch (action) {
            case CREATE, EDIT -> Set.of("learning-task:edit");
            case READ, ASSIGNMENT_READ, STATISTICS -> Set.of("learning-task:read");
            case PREVIEW, ADD -> Set.of("learning-task:assign");
            case PUBLISH -> Set.of("learning-task:publish", "learning-task:assign");
            case DEADLINE, CANCEL, CLOSE -> Set.of("learning-task:lifecycle");
            case REVOKE -> Set.of("learning-task:revoke");
            case REVOKE_PARTICIPATED -> Set.of("learning-task:revoke", "learning-task:revoke-participated");
            case REVIEW_READ -> Set.of("learning-submission:read");
            case REVIEW -> Set.of("learning-submission:read", "learning-submission:review");
            case MY_READ, CONFIRM, SUBMIT ->
                    throw new IllegalArgumentException("Ordinary session requires assignment ownership");
        };
    }
}
