package cn.jualn.miniapp.module.authorization.service;

import cn.jualn.miniapp.module.authorization.mapper.RbacMapper;
import cn.jualn.miniapp.module.authorization.model.RbacModels;
import cn.jualn.miniapp.module.authorization.support.RbacProblems;

import cn.jualn.miniapp.module.person.service.access.PersonAuthorityGate;

import java.time.LocalDateTime;
import java.util.Arrays;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Joint transaction entry: Person gate, governance mutex, then ordered User/Authority locks.
 * The existing governance mutex also protects account restriction and source writers.
 * No REQUIRES_NEW authorization or positive cache may be used after this boundary.
 */
@Service
@RequiredArgsConstructor
public class TransactionalAuthorization {
    private final RbacMapper mapper;
    private final RbacAuthorizationReader reader;
    private final PersonAuthorityGate personGate;

    @Transactional(propagation = Propagation.MANDATORY)
    public LocalDateTime now() {
        return mapper.now();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public long begin(PersonAuthorityGate.Access access, long... users) {
        long epoch = personGate.acquire(access);
        if (!"ROOT".equals(mapper.lockGovernance())) throw RbacProblems.unavailable();
        Arrays.stream(users).distinct().sorted().forEach(user -> {
            var subject = mapper.lockSubject(user);
            if (subject == null || subject.getDeletedAt() != null)
                throw RbacProblems.problem(org.springframework.http.HttpStatus.NOT_FOUND, "not-found");
            if (subject.getSource() == null || subject.getVersion() == null) throw RbacProblems.unavailable();
        });
        return epoch;
    }

    /**
     * Runtime capability readiness is owned here, not by a consumer reading RbacMapper.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean permissionReady(String permission) {
        return mapper.permissions().stream().anyMatch(p -> permission.equals(p.getCode())
                && "READY".equals(p.getReadiness()) && "ENABLED".equals(p.getLifecycle()));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public RbacModels.Decision current(long user, LocalDateTime evaluatedAt) {
        return reader.readCurrent(user, evaluatedAt);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public RbacModels.Decision currentNow(long user) {
        return reader.readCurrent(user, mapper.now());
    }
}
