package cn.jualn.miniapp.module.learningtask.service;

import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.module.article.service.ArticleCursor;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO;
import cn.jualn.miniapp.module.person.service.access.PersonAuthorityGate;
import cn.jualn.miniapp.module.authorization.model.RbacModels;
import cn.jualn.miniapp.module.authorization.service.TransactionalAuthorization;
import cn.jualn.miniapp.module.authorization.service.UnifiedDutyReader;
import cn.jualn.miniapp.module.authorization.model.UnifiedModels.Target;
import cn.jualn.miniapp.module.authorization.model.UnifiedModels.Selection;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Decision;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Action;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Audience;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.ReadScope;
import cn.jualn.miniapp.module.learningtask.mapper.LearningTaskMapper;
import cn.jualn.miniapp.module.person.service.access.PersonBusinessScopes;
import cn.jualn.miniapp.module.user.service.UserLearningTaskRecipientService;
import cn.jualn.miniapp.module.user.service.UserCurrentLoginAccess;
import cn.jualn.miniapp.module.learningtask.LearningTaskProblems.Dependency;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.*;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static cn.jualn.miniapp.module.learningtask.LearningTaskProblems.unavailable;
import static cn.jualn.miniapp.module.learningtask.LearningTaskProblems.conflict;
import static cn.jualn.miniapp.module.learningtask.LearningTaskProblems.invalid;
import static cn.jualn.miniapp.module.learningtask.LearningTaskProblems.missing;

/**
 * A task target and its audience must be covered by one complete, current source.
 */
@Service
@RequiredArgsConstructor
public class LearningTaskScopePolicy {
    private final PersonBusinessScopes persons;
    private final TransactionalAuthorization authorization;
    private final UnifiedDutyReader duties;
    private final LearningTaskMapper tasks;
    private final UserLearningTaskRecipientService recipients;
    private final UserCurrentLoginAccess accounts;
    private final LearningTaskResponsibilities responsibilities;
    private final Object contextKey = new Object();

    private static final class Context {
        final long actor;
        final List<Check> checks = new ArrayList<>();
        final List<Target> creations = new ArrayList<>();
        final List<Resolution> resolutions = new ArrayList<>();

        Context(long actor) {
            this.actor = actor;
        }
    }

    private record Resolution(long task, Audience audience,
                              List<LearningTaskBO.Recipient> users) {
    }

    private record Check(Action action, Long task) {
    }

    public void begin(long actor) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw unavailable(Dependency.AUTHORIZATION);
        var current = (Context) TransactionSynchronizationManager.getResource(contextKey);
        if (current != null) {
            if (current.actor != actor) throw unavailable(Dependency.AUTHORIZATION);
            return;
        }
        if (!Integer.valueOf(java.sql.Connection.TRANSACTION_REPEATABLE_READ).equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()))
            throw unavailable(Dependency.AUTHORIZATION);
        authorization.begin(PersonAuthorityGate.Access.SHARED, actor);
        accounts.require(actor);
        TransactionSynchronizationManager.bindResource(contextKey, new Context(actor));
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                TransactionSynchronizationManager.unbindResourceIfPossible(contextKey);
            }
        });
    }

    public RbacModels.Decision decision(long actor) {
        begin(actor);
        return authorization.currentNow(actor);
    }

    private LocalDateTime now() {
        return authorization.now();
    }

    private List<PersonBusinessScopes.Scope> sources(long actor, String code, String... permissions) {
        return duties.effectiveOperations(actor, code, now(), permissions);
    }

    private <T> T personFact(java.util.function.Supplier<T> read) {
        try {
            return read.get();
        } catch (ContractProblemException failure) {
            if (failure.getStatus() == HttpStatus.SERVICE_UNAVAILABLE) {
                var problem = unavailable(Dependency.PERSON_RESOLVER);
                problem.initCause(failure);
                throw problem;
            }
            if (failure.getStatus() == HttpStatus.CONFLICT)
                throw conflict("resource-unavailable");
            throw failure;
        }
    }

    private List<Target> audience(Selection s) {
        return s.audienceTargets() == null ? List.of(s.target()) : s.audienceTargets();
    }

    private Selection selection(PersonBusinessScopes.Scope s) {
        return duties.decode(s.selection(), Selection.class);
    }

    private boolean covers(Target ceiling, Target target) {
        return ceiling != null && target != null && ("PLATFORM".equals(ceiling.type()) || ceiling.equals(target));
    }

    private Target taskTarget(long task) {
        var row = tasks.selectById(task);
        if (row == null) throw missing();
        if (row.getManagementTarget() == null) throw unavailable(Dependency.AUTHORIZATION);
        return storedTarget(row.getManagementTarget());
    }

    private Target storedTarget(String value) {
        var target = duties.decode(value, Target.class);
        if (target == null || target.personId() != null || !("PLATFORM".equals(target.type()) && target.organizationId() == null
                || "ORGANIZATION".equals(target.type()) && target.organizationId() != null))
            throw unavailable(Dependency.AUTHORIZATION);
        return target;
    }

    private boolean review(Action action) {
        return action == Action.REVIEW || action == Action.REVIEW_READ;
    }

    public void task(long actor, Action action, Long task) {
        begin(actor);
        if (task != null) requireTask(actor, action, task);
        ((Context) TransactionSynchronizationManager.getResource(contextKey)).checks.add(new Check(action, task));
    }

    private void requireTask(long actor, Action action, long task) {
        Target target = taskTarget(task);
        if (authorization.currentNow(actor).managementOwner("LEARNING_TASK", LearningTaskAuthorizationPolicy.permissions(action).toArray(String[]::new)) != null)
            return;
        if (sources(actor, review(action) ? "LEARNING_REVIEW" : "LEARNING_MANAGE", LearningTaskAuthorizationPolicy.permissions(action).toArray(String[]::new)).stream().noneMatch(s -> covers(selection(s).target(), target)))
            throw missing();
    }

    public void creation(long actor, Target target) {
        begin(actor);
        requireCreation(actor, target);
        ((Context) TransactionSynchronizationManager.getResource(contextKey)).creations.add(target);
    }

    private void requireCreation(long actor, Target target) {
        if (target == null || target.personId() != null || !("PLATFORM".equals(target.type()) && target.organizationId() == null || "ORGANIZATION".equals(target.type()) && target.organizationId() != null))
            throw invalid("managementTarget");
        if ("ORGANIZATION".equals(target.type())) personFact(() -> {
            persons.requireOrganization(target.organizationId());
            return null;
        });
        if (authorization.currentNow(actor).managementOwner("LEARNING_TASK", "learning-task:edit") != null) return;
        if (sources(actor, "LEARNING_MANAGE", "learning-task:edit").stream().noneMatch(s -> covers(selection(s).target(), target)))
            throw missing();
    }

    public ReadScope readable(long actor, boolean review) {
        begin(actor);
        var sources = sources(actor, review ? "LEARNING_REVIEW" : "LEARNING_MANAGE");
        boolean owner = authorization.currentNow(actor).managementOwner("LEARNING_TASK", review ? "learning-submission:read" : "learning-task:read") != null;
        var ids = tasks.selectList(null).stream().filter(t -> t.getManagementTarget() != null).filter(t -> {
            var target = storedTarget(t.getManagementTarget());
            return owner || sources.stream().anyMatch(s -> covers(selection(s).target(), target));
        }).map(t -> t.getId()).toList();
        return new ReadScope(ids);
    }

    private boolean personCovered(String person, List<Target> ceilings, LocalDateTime at) {
        if (ceilings.stream().anyMatch(t -> "PLATFORM".equals(t.type()) || "PERSON".equals(t.type()) && person.equals(t.personId())))
            return true;
        RuntimeException unknown = null;
        for (var t : ceilings)
            if ("ORGANIZATION".equals(t.type())) try {
                if (personFact(() -> persons.cycle(person, t.organizationId(), at)) != null) return true;
            } catch (ContractProblemException failure) {
                if (failure.getStatus() == HttpStatus.SERVICE_UNAVAILABLE) unknown = failure;
                else throw failure;
            }
        if (unknown != null) throw unknown;
        return false;
    }

    private boolean accountCovered(long user, List<Target> ceilings, LocalDateTime at) {
        if (ceilings.stream().anyMatch(t -> "PLATFORM".equals(t.type()))) return true;
        String person = responsibilities.accountPerson(user);
        return person != null && personCovered(person, ceilings, at);
    }

    private List<String> expand(List<Target> targets, LocalDateTime at) {
        var result = new TreeSet<String>();
        for (var t : targets)
            switch (t.type()) {
                case "PLATFORM" -> result.addAll(persons.people());
                case "PERSON" -> result.add(t.personId());
                case "ORGANIZATION" -> result.addAll(personFact(() -> persons.rosterMembers(t.organizationId(), at)));
                default -> throw unavailable(Dependency.PERSON_RESOLVER);
            }
        return List.copyOf(result);
    }

    public Target optionTarget(long actor, Long task, String type, String org) {
        begin(actor);
        if (task != null) {
            if (type != null || org != null) throw invalid("managementTarget");
            requireTask(actor, Action.READ, task);
            var row = tasks.selectById(task);
            if (row == null) throw missing();
            if (!"DRAFT".equals(row.getStatus()) && !"PUBLISHED".equals(row.getStatus()))
                throw conflict("state-conflict");
            return taskTarget(task);
        }
        var target = new Target(type, null, org);
        if (target.personId() != null || !("PLATFORM".equals(target.type()) && target.organizationId() == null
                || "ORGANIZATION".equals(target.type()) && target.organizationId() != null)) throw invalid("managementTarget");
        if ("ORGANIZATION".equals(target.type())) personFact(() -> { persons.requireOrganization(target.organizationId()); return null; });
        if (optionCeilings(actor, target, true).isEmpty()) throw missing();
        return target;
    }

    public List<Target> selectedRecipientCeilings(long actor, long task) {
        begin(actor);
        requireTask(actor, Action.READ, task);
        return recipientCeilings(actor, taskTarget(task), "learning-task:read");
    }

    // Options are a search of known candidates, not an expansion of the entire roster.
    public List<Target> optionCeilings(long actor, Target target, boolean draft) {
        var result = new ArrayList<>(recipientCeilings(actor, target, "learning-task:assign"));
        if (draft) result.addAll(recipientCeilings(actor, target, "learning-task:edit"));
        return result.stream().distinct().toList();
    }

    public boolean draftOptions(Long task) {
        return task == null || "DRAFT".equals(tasks.selectById(task).getStatus());
    }

    private List<Target> recipientCeilings(long actor, Target target, String permission) {
        begin(actor);
        if (authorization.currentNow(actor).managementOwner("LEARNING_TASK", permission) != null)
            return List.of(new Target("PLATFORM", null, null));
        var result = sources(actor, "LEARNING_MANAGE", permission).stream().filter(s -> covers(selection(s).target(), target))
                .flatMap(s -> audience(selection(s)).stream()).distinct().toList();
        return result;
    }

    public boolean selectedRecipientCovered(
            LearningTaskBO.Subject subject, List<Target> ceilings) {
        if (ceilings.stream().anyMatch(t -> "PLATFORM".equals(t.type()))) return true;
        if (ceilings.isEmpty()) return false;
        String person = "PERSON".equals(subject.type()) ? subject.personId()
                : personFact(() -> persons.trustedBoundPerson(Long.parseLong(subject.userId())));
        return person != null && personCovered(person, ceilings, now());
    }

    public boolean optionCovered(String type, String id, List<Target> ceilings) {
        return "PERSON".equals(type) ? personCovered(id, ceilings, now()) : accountCovered(Long.parseLong(id), ceilings, now());
    }

    public String articleOptionsVersion(long actor, Long task, String type, String org) {
        begin(actor);
        var d = authorization.currentNow(actor);
        return ArticleCursor.fingerprint(Arrays.asList(actor, task, type, org, task == null ? null : taskTarget(task), persons.epoch(), d.authorizationVersion(), d.catalogVersion(), duties.encode(sources(actor, "LEARNING_MANAGE"))));
    }

    public String audienceVersion(long actor, long task) {
        begin(actor);
        var d = authorization.currentNow(actor);
        String value = persons.epoch() + "|" + d.authorizationVersion() + "|" + d.catalogVersion() + "|" + taskTarget(task) + "|" + sources(actor, "LEARNING_MANAGE");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    public List<LearningTaskBO.Recipient> resolve(long actor, long task, Audience audience) {
        begin(actor);
        if (audience == null) throw invalid("audience");
        var at = now();
        var target = taskTarget(task);
        var candidates = sources(actor, "LEARNING_MANAGE", "learning-task:assign").stream().filter(s -> covers(selection(s).target(), target)).toList();
        var people = new TreeSet<String>(audience.personIds());
        var accounts = new TreeSet<Long>();
        for (var id : audience.accountUserIds())
            try {
                long value = Long.parseLong(id);
                if (value <= 0 || !Long.toString(value).equals(id)) throw new NumberFormatException();
                accounts.add(value);
            } catch (NumberFormatException failure) {
                throw missing();
            }
        for (var filter : audience.filters()) {
            if (!filter.identityIds().isEmpty()) throw unavailable(Dependency.PERSON_RESOLVER);
            personFact(() -> {
                persons.requireDevelopmentStages(filter.developmentStageIds());
                return null;
            });
            var pool = new TreeSet<String>();
            if (filter.organizationIds().isEmpty()) pool.addAll(persons.people());
            else for (var org : filter.organizationIds()) pool.addAll(personFact(() -> persons.rosterMembers(org, at)));
            if (!filter.developmentStageIds().isEmpty())
                pool.removeIf(person -> !filter.developmentStageIds().contains(personFact(() -> persons.developmentStage(person, at))));
            people.addAll(pool);
        }
        if (audience.authorizedScopeId() != null) {
            var source = candidates.stream().filter(c -> c.id().equals(audience.authorizedScopeId())).findFirst().orElseThrow(cn.jualn.miniapp.module.learningtask.LearningTaskProblems::missing);
            people.addAll(expand(audience(selection(source)), at));
        }
        if (people.size() + accounts.size() > 1000) throw invalid("audience");
        boolean covered = audience.authorizedScopeId() == null && authorization.currentNow(actor).managementOwner("LEARNING_TASK", "learning-task:assign") != null;
        for (var candidate : candidates) {
            if (audience.authorizedScopeId() != null && !candidate.id().equals(audience.authorizedScopeId())) continue;
            var ceilings = audience(selection(candidate));
            if (people.stream().allMatch(person -> personCovered(person, ceilings, at)) && accounts.stream().allMatch(user -> accountCovered(user, ceilings, at))) {
                covered = true;
                break;
            }
        }
        if (!covered) throw missing();
        var result = new ArrayList<LearningTaskBO.Recipient>();
        for (var person : people)
            result.add(responsibilities.recipient(LearningTaskBO.Subject.person(person)));
        for (var account : accounts)
            result.add(responsibilities.recipient(LearningTaskBO.Subject.account(account)));
        var resolved = result.stream().sorted(Comparator.comparing(r -> r.subject().key())).toList();
        var context = (Context) TransactionSynchronizationManager.getResource(contextKey);
        var resolution = new Resolution(task, audience, resolved);
        if (!context.resolutions.contains(resolution)) context.resolutions.add(resolution);
        return resolved;
    }

    public void finish(long actor, boolean user) {
        begin(actor);
        accounts.require(actor);
        if (user) return;
        var c = (Context) TransactionSynchronizationManager.getResource(contextKey);
        for (var check : c.checks) {
            var d = authorization.currentNow(actor);
            if (!LearningTaskAuthorizationPolicy.permissions(check.action()).stream().allMatch(p -> d.allows(p, "LEARNING_TASK")))
                throw missing();
            if (check.task() != null) requireTask(actor, check.action(), check.task());
        }
        for (var target : c.creations) requireCreation(actor, target);
        for (var r : List.copyOf(c.resolutions))
            if (!r.users().equals(resolve(actor, r.task(), r.audience()))) throw conflict("preview-stale");
    }
}
