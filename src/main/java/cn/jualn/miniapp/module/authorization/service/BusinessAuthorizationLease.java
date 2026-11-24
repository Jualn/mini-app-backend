package cn.jualn.miniapp.module.authorization.service;

import cn.jualn.miniapp.module.authorization.model.RbacModels;
import cn.jualn.miniapp.module.authorization.support.RbacProblems;

import cn.jualn.miniapp.module.person.service.access.PersonAuthorityGate;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.function.Consumer;

/**
 * Existing authority gate and governance mutex serialize local effects with source/account revocation.
 * Fresh primary reads before work and immediately before commit also reject expiry during the operation.
 * External candidate checks run outside this lease; the final writer obtains its own lease.
 */
@Service
@RequiredArgsConstructor
public class BusinessAuthorizationLease {
    private final TransactionalAuthorization locks;
    private final RbacAuthorizationReader reader;

    public void require(long actor, Consumer<RbacModels.Decision> check) {
        boolean write = TransactionSynchronizationManager.isActualTransactionActive()
                && !TransactionSynchronizationManager.isCurrentTransactionReadOnly();
        if (write) {
            if (!Integer.valueOf(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED).equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()))
                throw RbacProblems.unavailable();
            locks.begin(cn.jualn.miniapp.module.person.service.access.PersonAuthorityGate.Access.SHARED, actor);
            check.accept(locks.currentNow(actor));
        } else check.accept(reader.read(actor));
        if (write) TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void beforeCommit(boolean readOnly) {
                check.accept(locks.currentNow(actor));
            }
        });
    }
}
