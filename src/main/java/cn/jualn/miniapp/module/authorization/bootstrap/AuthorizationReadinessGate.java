package cn.jualn.miniapp.module.authorization.bootstrap;

import cn.jualn.miniapp.module.authorization.service.AuthorizationAuthorityService;

import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;

/**
 * Fail startup before serving users when the explicit pre-deployment backfill is incomplete.
 */
@Component
@DependsOnDatabaseInitialization
@RequiredArgsConstructor
public class AuthorizationReadinessGate implements SmartInitializingSingleton {
    private final AuthorizationAuthorityService authorities;

    @Override
    public void afterSingletonsInstantiated() {
        var coverage = authorities.coverage();
        if (coverage.missing() != 0 || coverage.corrupt() != 0 || coverage.legacy() != 0 || authorities.rootSubjects() != 1) throw new IllegalStateException(
                "ASSIGNMENT-only authority coverage required; inspect sources and establish the approved unique ROOT before switching");
    }
}
