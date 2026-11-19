package cn.jualn.miniapp.module.authorization.service;

import cn.jualn.miniapp.module.authorization.mapper.RbacMapper;
import cn.jualn.miniapp.module.authorization.support.RbacProblems;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

import static cn.jualn.miniapp.module.authorization.model.RbacModels.*;

/** Protects existing administrators and the sole ROOT during account changes. */
@Service
@RequiredArgsConstructor
public class RbacGovernanceService {
    private final RbacMapper mapper;
    private final RbacAuthorizationReader reader;

    private void requireRoot(long actor, LocalDateTime now) {
        Decision decision = reader.readCurrent(actor, now);
        boolean root = decision.accountEligible() && decision.source() == Source.ASSIGNMENT
                && decision.grants().stream().anyMatch(g -> "ROOT".equals(g.roleCode()) && g.allows("authorization:root-manage", "AUTHORIZATION")
                && g.allows("authorization:assignment-manage", "AUTHORIZATION"));
        if (!root) throw RbacProblems.problem(HttpStatus.FORBIDDEN, "authorization-management-forbidden");
    }

    private Set<Long> validRootUsers(LocalDateTime now) {
        Set<Long> users = new HashSet<>();
        for (long user : mapper.rootUsers()) {
            Decision d = reader.readCurrent(user, now);
            if (d.grants().stream().anyMatch(g -> "ROOT".equals(g.roleCode()) && g.allows("authorization:root-manage", "AUTHORIZATION")))
                users.add(user);
        }
        return users;
    }

    /**
     * Next-slice account/source writers must call first, in their OWN transaction, before User locks.
     * The ROOT-row lock remains held until their commit. This method never commits independently.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void protectAccountChange(long actor, long target) {
        if (!Objects.equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel(),
                TransactionDefinition.ISOLATION_READ_COMMITTED)) {
            throw new IllegalTransactionStateException("ROOT guard requires an explicit READ_COMMITTED owner transaction");
        }
        if (!"ROOT".equals(mapper.lockGovernance())) throw RbacProblems.unavailable();
        lockSubjects(actor, target);
        Subject targetSubject = mapper.subject(target);
        if (targetSubject != null) {
            if (targetSubject.getVersion() == null || targetSubject.getVersion().isBlank())
                throw RbacProblems.unavailable();
            if (!"LEGACY".equals(targetSubject.getSource()) && !"ASSIGNMENT".equals(targetSubject.getSource()))
                throw RbacProblems.unavailable();
            // Account safety is separate from old scalar-role immunity. Protect every account
            // carrying legacy management authority or Assignment governance history.
            if ("ASSIGNMENT".equals(targetSubject.getSource()) && (!mapper.assignments(target).isEmpty()
                        || reader.readCurrent(target, mapper.now()).delegationEligible())) {
                requireRoot(actor, mapper.now());
                if (actor == target) throw RbacProblems.problem(HttpStatus.FORBIDDEN, "authorization-self-change-forbidden");
            }
            if ("LEGACY".equals(targetSubject.getSource())) return; // Shadow ROOT is not active.
        }
        if (!mapper.rootUsers().contains(target)) return;
        LocalDateTime now = mapper.now();
        requireRoot(actor, now);
        if (actor == target) throw RbacProblems.problem(HttpStatus.FORBIDDEN, "authorization-self-change-forbidden");
        Set<Long> currentRoots = validRootUsers(now);
        if (currentRoots.contains(target) && currentRoots.size() <= 1)
            throw conflict("authorization-last-root-protected");
    }

    private void lockSubjects(long actor, long target) {
        mapper.lockSubject(Math.min(actor, target));
        if (actor != target) mapper.lockSubject(Math.max(actor, target));
    }

    private static RuntimeException conflict(String type) {
        return RbacProblems.problem(HttpStatus.CONFLICT, type);
    }
}
