package cn.jualn.miniapp.module.person.service.facts;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonValue;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import cn.jualn.miniapp.module.person.bo.PersonBO.Domain;
import cn.jualn.miniapp.module.person.bo.PersonBO.Action;

import static cn.jualn.miniapp.module.person.PersonProblems.unavailable;

/**
 * Unforgeable in-process transaction handle. Not an HTTP DTO or a cacheable ALLOW.
 */
public final class PersonReadContext {
    private static final Object RESOURCE = new Object();
    private final Object transactionIdentity;
    private final Thread thread = Thread.currentThread();
    private final String txContextId = UUID.randomUUID().toString();
    private final long authorityVersion;
    private final LocalDateTime evaluatedAt;
    private LocalDateTime finalEvaluatedAt;
    private final java.util.Set<Long> lockedUsers;
    private boolean active = true;
    private final Map<String, Plan> plans = new HashMap<>();

    record Plan(long actor, Domain domain, Action action, String organizationId) {
    }

    PersonReadContext(long epoch, LocalDateTime now, long... users) {
        if (!TransactionSynchronizationManager.isActualTransactionActive() || !TransactionSynchronizationManager.isSynchronizationActive()
                || TransactionSynchronizationManager.hasResource(RESOURCE)) throw unavailable();
        transactionIdentity = new Object();
        authorityVersion = epoch;
        evaluatedAt = now;
        lockedUsers = java.util.Arrays.stream(users).boxed().collect(java.util.stream.Collectors.toUnmodifiableSet());
        TransactionSynchronizationManager.bindResource(RESOURCE, transactionIdentity);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void suspend() {
                TransactionSynchronizationManager.unbindResource(RESOURCE);
            }

            @Override
            public void resume() {
                TransactionSynchronizationManager.bindResource(RESOURCE, transactionIdentity);
            }

            @Override
            public void afterCompletion(int status) {
                active = false;
                plans.clear();
                TransactionSynchronizationManager.unbindResourceIfPossible(RESOURCE);
            }
        });
    }

    public String protocolVersion() {
        return "1";
    }

    public String txContextId() {
        return txContextId;
    }

    public String authorityVersion() {
        return Long.toString(authorityVersion);
    }

    public LocalDateTime evaluatedAt() {
        return evaluatedAt;
    }

    public void beginFinalCheck(LocalDateTime at) {
        require();
        if (at.isBefore(evaluatedAt)) throw unavailable();
        finalEvaluatedAt = at;
    }

    public LocalDateTime finalEvaluatedAt() {
        require();
        if (finalEvaluatedAt == null) throw unavailable();
        return finalEvaluatedAt;
    }

    public record Snapshot(String protocolVersion, String txContextId, String authorityVersion, String evaluatedAt) {
    }

    /**
     * Serializable evidence is metadata only; it cannot be deserialized into a transaction handle.
     */
    @JsonValue
    public Snapshot snapshot() {
        require();
        return new Snapshot(protocolVersion(), txContextId, authorityVersion(), java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(evaluatedAt.atOffset(java.time.ZoneOffset.UTC)));
    }

    public void require() {
        if (!active || thread != Thread.currentThread() || !TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.getResource(RESOURCE) != transactionIdentity) throw unavailable();
    }

    public void requireUser(long user) {
        require();
        if (!lockedUsers.contains(user)) throw unavailable();
    }

    public void requirePerson(String person) {
        require();
        if (person == null || person.isBlank()) throw unavailable();
    }

    public String plan(Plan plan) {
        require();
        String id = UUID.randomUUID().toString();
        plans.put(id, plan);
        return id;
    }

    public Plan plan(String id) {
        require();
        var plan = plans.get(id);
        if (plan == null) throw unavailable();
        return plan;
    }
}
