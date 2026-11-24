package cn.jualn.miniapp.module.points.service;

import cn.jualn.miniapp.module.points.PointsProblems.Dependency;
import cn.dev33.satoken.stp.StpUtil;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.module.points.PointsHttpAccess;
import cn.jualn.miniapp.module.admin.auth.service.AdminTokenService;
import cn.jualn.miniapp.module.authorization.service.CurrentAuthorization;
import cn.jualn.miniapp.module.authorization.service.RbacCatalog;
import cn.jualn.miniapp.module.authorization.model.RbacModels.Source;
import cn.jualn.miniapp.module.points.bo.PointsBO.Action;
import cn.jualn.miniapp.module.points.bo.PointsBO.Application;
import cn.jualn.miniapp.module.points.bo.PointsBO.Ledger;

import cn.jualn.miniapp.module.user.service.UserAccountService;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import static cn.jualn.miniapp.module.points.PointsProblems.unavailable;

/** Deliberately closed: the canonical handoff has unresolved B01/B02 and media/consumer prerequisites. */
public class UnavailablePointsDependencies implements PointsDependencies {
    private final CurrentAuthorization authorization;
    private final RbacCatalog catalog;
    private final UserAccountService users;
    private final AdminTokenService tokens;
    public UnavailablePointsDependencies(CurrentAuthorization authorization,RbacCatalog catalog,UserAccountService users,AdminTokenService tokens) {
        this.authorization=authorization;this.catalog=catalog;this.users=users;this.tokens=tokens;
    }
    public long actor(Action action) {
        if(action==Action.MY_READ || action==Action.CREATE || action==Action.ELIGIBILITY || action==Action.CLOSE) {
            StpUtil.checkLogin();
            long user=StpUtil.getLoginIdAsLong();
            users.assertLoginAllowed(user);
            return user;
        }
        long actor=PointsHttpAccess.authenticate(tokens);
        requireAdmin(actor,action);
        return actor;
    }
    public void requireAdmin(long actor,Action action) {
        var codes=permissions(action);
        cn.jualn.miniapp.module.authorization.model.RbacModels.Decision decision;
        try { decision=authorization.current(actor); }
        catch(ContractProblemException failure) {
            if(failure.getStatus()!=HttpStatus.SERVICE_UNAVAILABLE) throw failure;
            var problem=unavailable(Dependency.AUTHORIZATION);problem.initCause(failure);throw problem;
        }
        if(decision.userId()!=actor || !decision.accountEligible() || decision.source()!=Source.ASSIGNMENT) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
        if(!codes.stream().allMatch(code->decision.allows(code,"POINTS"))) throw new BusinessException(ResultCode.FORBIDDEN);
        if(!catalog.supports("POINTS",codes)) throw unavailable(Dependency.AUTHORIZATION);
    }
    public static Set<String> permissions(Action action) {
        return switch(action) {
            case READ -> Set.of("points:read");
            case REVIEW, MATERIAL_READ -> Set.of("points:read","points:review");
            case REVERSE, CORRECT -> Set.of("points:read","points:adjust","points:adjust-approve");
            default -> throw new IllegalArgumentException("Ordinary session has no admin capabilities");
        };
    }
    public boolean eligible(long userId) { throw unavailable(Dependency.BUSINESS_ELIGIBILITY); }
    public void bindFiles(long userId,long applicationId,List<String> fileIds) { throw unavailable(Dependency.PRIVATE_MEDIA); }
    public void requireFiles(Application application) { throw unavailable(Dependency.PRIVATE_MEDIA); }
    public void acceptResult(Application application,Ledger reversal) { throw unavailable(Dependency.NOTIFICATION_ACCEPTANCE); }
}
