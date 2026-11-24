package cn.jualn.miniapp.module.home.service;

import cn.dev33.satoken.jwt.exception.SaJwtException;
import cn.dev33.satoken.stp.StpUtil;
import cn.jualn.miniapp.common.async.ContextCopyDecorator;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.common.web.AdminQuery;
import cn.jualn.miniapp.module.user.service.UserAccountService;
import jakarta.annotation.PreDestroy;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class HomeSummaryAccess {
    private final UserAccountService accounts;
    private final ContextCopyDecorator context = new ContextCopyDecorator();
    private final ExecutorService authentication = HomeSummaryService.executor("home-authentication");

    public HomeSummaryAccess(UserAccountService accounts) {
        this.accounts = accounts;
    }

    public long requireActor(String token, long requestStarted) {
        if (token == null || token.isBlank()) throw new BusinessException(ResultCode.UNAUTHORIZED);
        long remaining = HomeSummaryService.deadline(requestStarted) - System.nanoTime();
        if (remaining <= 0) throw AdminQuery.unavailable();

        // The worker uses the captured token. It does not hold servlet request or Sa-Token context.
        FutureTask<Long> task = new FutureTask<>(() -> authenticate(token));
        try {
            authentication.execute(context.decorate(task));
            return task.get(Math.max(0, HomeSummaryService.deadline(requestStarted) - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof BusinessException business) {
                switch (business.getResultCode()) {
                    case USER_NOT_FOUND -> throw new BusinessException(ResultCode.UNAUTHORIZED);
                    case UNAUTHORIZED, FORBIDDEN, USER_BANNED -> throw business;
                    default -> throw AdminQuery.unavailable();
                }
            }
            throw AdminQuery.unavailable();
        } catch (RejectedExecutionException | TimeoutException failure) {
            throw AdminQuery.unavailable();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw AdminQuery.unavailable();
        } finally {
            task.cancel(true);
        }
    }

    private long authenticate(String token) {
        var logic = StpUtil.getStpLogic();
        var loginId = logic.getLoginIdByToken(token);
        if (loginId == null) throw new BusinessException(ResultCode.UNAUTHORIZED);
        long actor;
        Object version;
        try {
            actor = Long.parseLong(loginId.toString());
            version = logic.getExtra(token, UserAccountService.SESSION_VERSION);
        } catch (NumberFormatException | SaJwtException failure) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        accounts.assertLoginAllowed(actor);
        accounts.requireSessionVersion(actor, version);
        return actor;
    }

    @PreDestroy
    void shutdown() {
        authentication.shutdownNow();
    }
}
