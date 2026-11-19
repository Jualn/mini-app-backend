package cn.jualn.miniapp.module.authorization.service;

import cn.jualn.miniapp.module.authorization.model.RbacModels.AuthorityCoverage;
import cn.jualn.miniapp.module.authorization.mapper.RbacMapper;
import cn.jualn.miniapp.module.authorization.support.RbacProblems;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.util.UUID;

/**
 * Controlled internal preparation only. No source-switch or public backfill endpoint.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuthorizationAuthorityService {
    private final RbacMapper mapper;

    public record BackfillReceipt(String batchId, long initialized, AuthorityCoverage before, AuthorityCoverage after) {
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void initializeNewUser(long user) {
        if (mapper.initializeAssignment(user, UUID.randomUUID().toString()) != 1) throw RbacProblems.unavailable();
    }

    public long rootSubjects() { return mapper.rootUsers().size(); }

    public AuthorityCoverage coverage() {
        return mapper.coverage();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public BackfillReceipt backfillLegacy() {
        throw new IllegalStateException("LEGACY initialization is retired; use an explicitly approved offline source plan");
    }
}
