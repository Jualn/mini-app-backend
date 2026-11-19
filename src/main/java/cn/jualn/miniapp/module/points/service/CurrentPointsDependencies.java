package cn.jualn.miniapp.module.points.service;

import cn.jualn.miniapp.module.person.service.access.PersonBusinessScopes;
import cn.jualn.miniapp.module.points.PointsProblems.Dependency;
import cn.dev33.satoken.stp.StpUtil;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.module.authorization.service.CurrentAuthorization;
import cn.jualn.miniapp.module.authorization.service.RbacCatalog;
import cn.jualn.miniapp.module.authorization.model.RbacModels;
import cn.jualn.miniapp.module.authorization.service.TransactionalAuthorization;
import cn.jualn.miniapp.module.admin.auth.service.AdminTokenService;
import cn.jualn.miniapp.module.person.bo.PersonBO;
import cn.jualn.miniapp.module.person.service.facts.PersonCatalog;
import cn.jualn.miniapp.module.person.service.facts.PersonFactReaders;
import cn.jualn.miniapp.module.person.service.facts.PersonReadContext;
import cn.jualn.miniapp.module.points.PointsHttpAccess;
import cn.jualn.miniapp.module.points.bo.PointsBO;
import cn.jualn.miniapp.module.points.bo.PointsBO.Acceptance;
import cn.jualn.miniapp.module.points.bo.PointsBO.AcceptingOrganizations;
import cn.jualn.miniapp.module.points.bo.PointsBO.Action;
import cn.jualn.miniapp.module.points.bo.PointsBO.Application;
import cn.jualn.miniapp.module.points.bo.PointsBO.AuthorizationAudit;
import cn.jualn.miniapp.module.points.bo.PointsBO.Eligibility;
import cn.jualn.miniapp.module.points.bo.PointsBO.Ledger;
import cn.jualn.miniapp.module.points.bo.PointsBO.Query;
import cn.jualn.miniapp.module.points.bo.PointsBO.Subject;
import cn.jualn.miniapp.module.points.mapper.PointsMapper;
import cn.jualn.miniapp.module.user.service.UserAccountService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.LongStream;

import cn.jualn.miniapp.module.user.service.UserCurrentLoginAccess;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import static cn.jualn.miniapp.module.points.PointsProblems.forbidden;
import static cn.jualn.miniapp.module.points.PointsProblems.missing;
import static cn.jualn.miniapp.module.points.PointsProblems.unavailable;

/**
 * Real owner facts and action-specific scopes; external effects remain independently gated.
 */
@Service
public class CurrentPointsDependencies extends UnavailablePointsDependencies {
    private static final Object RESOURCE = new Object();
    private final PersonFactReaders facts;
    private final PersonCatalog policy;
    private final TransactionalAuthorization authorization;
    private final AdminTokenService tokens;
    private final UserAccountService users;
    private final RbacCatalog catalog;
    private final PointsResultNotifications notifications;
    private final PointsPrivateMaterials materials;
    private final UserCurrentLoginAccess loginAccess;
    private final PointsMapper db;
    private final TransactionTemplate outside;
    private ObjectProvider<PointsAcceptancePolicy> acceptance;

    @Autowired
    void acceptance(ObjectProvider<PointsAcceptancePolicy> provider) {
        this.acceptance = provider;
    }

    private PointsAcceptancePolicy acceptance() {
        if (acceptance == null || acceptance.getIfAvailable() == null)
            throw unavailable(Dependency.BUSINESS_ELIGIBILITY);
        return acceptance.getObject();
    }

    private PersonBusinessScopes profiles;

    @Autowired
    void profiles(PersonBusinessScopes mapper) {
        this.profiles = mapper;
    }

    @Override
    public Subject subject(long user) {
        if (profiles == null) throw unavailable(Dependency.PERSON_RESOLVER);
        PersonBusinessScopes.AccountBinding binding;
        try {
            binding = profiles.currentAccountBinding(user);
        } catch (ContractProblemException failure) {
            if (failure.getStatus() == HttpStatus.FORBIDDEN) throw forbidden("ineligible");
            throw failure;
        }
        if (binding == null) throw forbidden("ineligible");
        var person = profiles.lifecycle(binding.personId());
        if (person == null) throw unavailable(Dependency.PERSON_RESOLVER);
        if (!"ACTIVE".equals(person.status())) throw forbidden("ineligible");
        return new Subject(binding.personId(), binding.bindingId());
    }

    @Override
    public boolean self(long user, String person) {
        if (profiles == null || person == null) throw unavailable(Dependency.PERSON_RESOLVER);
        return profiles.historicallyBound(person, user);
    }

    @Override
    public boolean approvalEligiblePerson(String person) {
        return stageEligiblePerson(person, false);
    }

    @Override
    public void requirePersonManage(long actor, String person, Action action) {
        managePerson(actor, person, action, false);
    }

    private void managePerson(long actor, String person, Action action, boolean last) {
        acceptance().ledger(actor, person, last ? context().finalEvaluatedAt() : context().evaluatedAt());
        var at = last ? context().finalEvaluatedAt() : context().evaluatedAt();
        if (action == Action.REVERSE && authorization.current(actor, at).managementOwner("POINTS", permissions(action).toArray(String[]::new)) == null) {
            var decision = last ? facts.authorizeFinal(actor, person, PersonBO.Domain.POINTS, PersonBO.Action.REVERSE, context()) : facts.authorizePerson(actor, person, PersonBO.Domain.POINTS, PersonBO.Action.REVERSE, context());
            if (decision.decision() == PersonFactReaders.Decision.INDETERMINATE)
                throw unavailable(Dependency.AUTHORIZATION);
            if (decision.decision() != PersonFactReaders.Decision.ALLOW) throw missing();
        }
    }

    @Override
    public void finishPerson(Action action, long actor, String person) {
        finalCheck(() -> {
            if (authenticate(action) != actor) throw new BusinessException(ResultCode.FORBIDDEN);
            var at = facts.beginFinalCheck(context());
            requireCurrent(actor, action, at);
            managePerson(actor, person, action, true);
        });
    }

    private static void finalCheck(Runnable check) {
        check.run();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void beforeCommit(boolean readOnly) {
                check.run();
            }
        });
    }

    public CurrentPointsDependencies(CurrentAuthorization current,
                                     RbacCatalog catalog,
                                     UserAccountService users,
                                     AdminTokenService tokens,
                                     PersonFactReaders facts,
                                     PersonCatalog policy,
                                     TransactionalAuthorization authorization,
                                     PlatformTransactionManager transactions,
                                     PointsResultNotifications notifications,
                                     PointsMapper db,
                                     PointsPrivateMaterials materials,
                                     UserCurrentLoginAccess loginAccess
    ) {
        super(current, catalog, users, tokens);
        this.facts = facts;
        this.policy = policy;
        this.authorization = authorization;
        this.tokens = tokens;
        this.users = users;
        this.catalog = catalog;
        this.notifications = notifications;
        this.db = db;
        this.materials = materials;
        this.loginAccess = loginAccess;
        outside = new TransactionTemplate(transactions);
        outside.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
    }

    private static boolean ordinary(Action action) {
        return action == Action.MY_READ || action == Action.CREATE || action == Action.ELIGIBILITY || action == Action.CLOSE;
    }

    @Override
    public long actor(Action action) {
        // Authentication may read User; suspend the not-yet-open transaction to avoid creating its MVCC snapshot before the gate.
        if (TransactionSynchronizationManager.isActualTransactionActive() && !TransactionSynchronizationManager.hasResource(RESOURCE))
            return outside.execute(status -> authenticate(action));
        long actor = authenticate(action);
        if (TransactionSynchronizationManager.hasResource(RESOURCE)) requireCurrent(actor, action);
        return actor;
    }

    private long authenticate(Action action) {
        if (!ordinary(action)) {
            long actor = TransactionSynchronizationManager.hasResource(RESOURCE) ? PointsHttpAccess.authenticateLocked(tokens) : PointsHttpAccess.authenticate(tokens);
            if (!TransactionSynchronizationManager.hasResource(RESOURCE)) requireAdmin(actor, action);
            return actor;
        }
        StpUtil.checkLogin();
        long user = StpUtil.getLoginIdAsLong();
        if (TransactionSynchronizationManager.hasResource(RESOURCE)) loginAccess.require(user);
        else users.assertLoginAllowed(user);
        return user;
    }

    @Override
    public void begin(Action action, long actor, long... targets) {
        if (TransactionSynchronizationManager.hasResource(RESOURCE)) throw unavailable(Dependency.AUTHORIZATION);
        try {
            var context = facts.open(LongStream.concat(LongStream.of(actor), LongStream.of(targets)).distinct().sorted().toArray());
            TransactionSynchronizationManager.bindResource(RESOURCE, context);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void suspend() {
                    TransactionSynchronizationManager.unbindResource(RESOURCE);
                }

                @Override
                public void resume() {
                    TransactionSynchronizationManager.bindResource(RESOURCE, context);
                }

                @Override
                public void afterCompletion(int status) {
                    TransactionSynchronizationManager.unbindResourceIfPossible(RESOURCE);
                }
            });
            if (authenticate(action) != actor) throw new BusinessException(ResultCode.FORBIDDEN);
            requireCurrent(actor, action);
        } catch (ContractProblemException failure) {
            throw mapped(failure, Dependency.AUTHORIZATION);
        }
    }

    private PersonReadContext context() {
        var context = (PersonReadContext) TransactionSynchronizationManager.getResource(RESOURCE);
        if (context == null) throw unavailable(Dependency.AUTHORIZATION);
        return context;
    }

    @Override
    public LocalDateTime evaluatedAt(LocalDateTime fallback) {
        return context().evaluatedAt();
    }

    private void requireCurrent(long actor, Action action) {
        requireCurrent(actor, action, null);
    }

    private void requireCurrent(long actor, Action action, LocalDateTime at) {
        try {
            if (ordinary(action)) {
                loginAccess.require(actor);
                return;
            }
            var decision = at == null ? authorization.currentNow(actor) : authorization.current(actor, at);
            if (!decision.accountEligible()) throw new BusinessException(ResultCode.FORBIDDEN);
            if (decision.source() != RbacModels.Source.ASSIGNMENT || !permissions(action).stream().allMatch(code -> decision.allows(code, "POINTS")))
                throw new BusinessException(ResultCode.FORBIDDEN);
            if (!catalog.supports("POINTS", permissions(action))) throw unavailable(Dependency.AUTHORIZATION);
        } catch (DataAccessException failure) {
            var problem = unavailable(Dependency.AUTHORIZATION);
            problem.initCause(failure);
            throw problem;
        }
    }

    @Override
    public boolean eligible(long user) {
        return stageEligible(user, false) && acceptance().hasAcceptingOrganization(user, context(), false);
    }

    @Override
    public void finishAdmission(long user) {
        finish(Action.CREATE, user, user, true);
        if (!acceptance().hasAcceptingOrganization(user, context(), true)) throw forbidden("ineligible");
    }

    @Override
    public Eligibility eligibility(long user, LocalDateTime at) {
        if (profiles == null) throw unavailable(Dependency.PERSON_RESOLVER);
        PersonBusinessScopes.AccountBinding binding;
        try {
            binding = profiles.currentAccountBinding(user);
        } catch (ContractProblemException failure) {
            if (failure.getStatus() == HttpStatus.FORBIDDEN) return new Eligibility(false, at, "PERSON_UNAVAILABLE");
            throw failure;
        }
        if (binding == null) return new Eligibility(false, at, "NO_BINDING");
        var profile = profiles.lifecycle(binding.personId());
        if (profile == null) throw unavailable(Dependency.PERSON_RESOLVER);
        if (!"ACTIVE".equals(profile.status())) return new Eligibility(false, at, "PERSON_UNAVAILABLE");
        if (!stageEligible(user, false)) return new Eligibility(false, at, "BUSINESS_STAGE_INELIGIBLE");
        boolean allowed = acceptance().hasAcceptingOrganization(user, context(), false);
        return new Eligibility(allowed, at, allowed ? null : "NO_ACCEPTING_ORGANIZATION");
    }

    @Override
    public AcceptingOrganizations acceptingOrganizations(long user) {
        return acceptance().candidates(user, context());
    }

    @Override
    public Acceptance acceptance(long user, String organization) {
        if (!stageEligible(user, false)) throw forbidden("ineligible");
        return acceptance().bind(user, organization, context());
    }

    @Override
    public void recheckAcceptance(Application application) {
        acceptance().currentBasis(application, context(), true);
    }

    @Override
    public PointsAcceptancePolicy.Plan applicationPlan(long actor, String person, String organization, Query query) {
        return acceptance().applications(actor, person, organization, query, context());
    }

    @Override
    public PointsAcceptancePolicy.Plan personPlan(long actor, String person, String organization) {
        return acceptance().people(actor, person, organization, context());
    }

    @Override
    public PointsAcceptancePolicy.Plan statisticsPlan(long actor, String organization, boolean accepting) {
        return acceptance().statistics(actor, organization, accepting, context());
    }

    @Override
    public void requireApplication(long actor, Application application, Action action) {
        acceptance().application(actor, application, action, context(), false);
    }

    @Override
    public void finishApplication(Action action, long actor, Application application, boolean eligibilityRequired) {
        finalCheck(() -> {
            if (authenticate(action) != actor) throw new BusinessException(ResultCode.FORBIDDEN);
            var at = facts.beginFinalCheck(context());
            requireCurrent(actor, action, at);
            if (!ordinary(action)) acceptance().application(actor, application, action, context(), true);
            else if (!subject(actor).personId().equals(application.personId())) throw missing();
            if (eligibilityRequired && !stageEligiblePerson(application.personId(), true))
                throw forbidden("ineligible");
            if (action == Action.CREATE || action == Action.REVIEW)
                acceptance().currentBasis(application, context(), true);
        });
    }

    private boolean stageEligible(long user, boolean currentTime) {
        loginAccess.require(user);
        return stageEligiblePerson(subject(user).personId(), currentTime);
    }

    private boolean stageEligiblePerson(String person, boolean currentTime) {
        if (!policy.pointsPolicyReady()) throw unavailable(Dependency.BUSINESS_ELIGIBILITY);
        if (profiles == null) throw unavailable(Dependency.PERSON_RESOLVER);
        var profile = profiles.lifecycle(person);
        if (profile == null) throw unavailable(Dependency.PERSON_RESOLVER);
        if (!"ACTIVE".equals(profile.status())) return false;
        var c = context();
        var fact = currentTime ? facts.identityFinal(person, c) : facts.identity(person, c);
        if (fact.outcome() != PersonFactReaders.Outcome.KNOWN) throw unavailable(Dependency.BUSINESS_ELIGIBILITY);
        if ("NONE".equals(fact.presence())) return false;
        var at = currentTime ? c.finalEvaluatedAt() : c.evaluatedAt();
        return "ACTIVE".equals(fact.status()) && policy.pointsEligibleStage(fact.stageId()) && !fact.validFrom().isAfter(at)
                && (fact.validUntil() == null || fact.validUntil().isAfter(at));
    }

    @Override
    public void acceptCorrection(Application application, PointsBO.Correction correction) {
        notifications.acceptCorrection(application, correction);
    }

    @Override
    public void acceptResult(Application application, Ledger reversal) {
        notifications.accept(application, reversal);
    }

    @Override
    public void bindFiles(long user, long application, List<String> files) {
        materials.bind(user, application, files);
    }

    @Override
    public void requireFiles(Application application) {
        materials.require(application);
    }

    @Override
    public void audit(Action action, long actor, long user, long application, Long ledger, String key) {
        var c = context();
        db.audit(new AuthorizationAudit(actor, user, action.name(), application, ledger, key, c.txContextId(), Long.parseLong(c.authorityVersion()),
                c.evaluatedAt(), c.finalEvaluatedAt(), policy.pointsPolicySource()));
    }

    @Override
    public void finish(Action action, long actor, long user, boolean eligibilityRequired) {
        finalCheck(() -> {
            if (authenticate(action) != actor) throw new BusinessException(ResultCode.FORBIDDEN);
            var at = facts.beginFinalCheck(context());
            requireCurrent(actor, action, at);
            if (!ordinary(action) || actor != user) throw unavailable(Dependency.AUTHORIZATION);
            if (eligibilityRequired && !stageEligible(user, true)) throw forbidden("ineligible");
        });
    }

    @Override
    public PersonFactReaders.PointsQuery query(long actor, String person, String organizationId) {
        var result = facts.pointsPlan(actor, person, organizationId, context());
        if ("DENIED".equals(result.decision())) throw new BusinessException(ResultCode.FORBIDDEN);
        if (!"READY".equals(result.decision()))
            throw unavailable(result.reason().startsWith("MEMBERSHIP") ? Dependency.PERSON_RESOLVER : Dependency.AUTHORIZATION);
        return result.plan();
    }

    private ContractProblemException mapped(ContractProblemException failure, Dependency dependency) {
        if (failure.getStatus() != HttpStatus.SERVICE_UNAVAILABLE) return failure;
        var result = unavailable(dependency);
        result.initCause(failure);
        return result;
    }
}
