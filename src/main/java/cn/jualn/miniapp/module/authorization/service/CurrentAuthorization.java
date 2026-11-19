package cn.jualn.miniapp.module.authorization.service;

import cn.jualn.miniapp.module.authorization.support.RbacProblems;

import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.ResultCode;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

import cn.jualn.miniapp.module.authorization.model.RbacModels.*;

/**
 * Source dispatcher. Legacy codes describe existing effects, never new grants.
 */
@Service
@RequiredArgsConstructor
public class CurrentAuthorization {
    private final RbacAuthorizationReader reader;
    private BusinessAuthorizationLease lease;

    @Autowired
    void lease(ObjectProvider<BusinessAuthorizationLease> provider) {
        lease = provider.getIfAvailable();
    }

    public void requireArticle(long user, String permission) {
        java.util.function.Consumer<Decision> check = d -> {
            if (!d.allows(permission, "ARTICLE")) throw new BusinessException(ResultCode.FORBIDDEN);
        };
        if (lease == null) check.accept(reader.read(user));
        else lease.require(user, check);
    }

    public void requireWork(long user, String duty, String namespace, String... permissions) {
        java.util.function.Consumer<Decision> check = d -> {
            if (!d.allowsWork(duty, namespace, permissions)) throw new BusinessException(ResultCode.FORBIDDEN);
        };
        if (lease == null) check.accept(reader.read(user));
        else lease.require(user, check);
    }

    /** Platform business effects need one explicit duty witness or a current ROOT owner capability.
     * GLOBAL alone never supplies a business target. */
    public void requirePlatform(long user, String legacyPermission, String namespace, String duty, String... permissions) {
        if (permissions.length == 0) throw new BusinessException(ResultCode.FORBIDDEN);
        java.util.function.Consumer<Decision> check = decision -> {
            boolean owner = decision.ownerCapabilities().stream().anyMatch(capability ->
                    decision.owns(capability.rootAssignmentId()) && namespace.equals(capability.namespace())
                    && "GLOBAL".equals(capability.scopeType())
                    && capability.permissionCodes().containsAll(java.util.Arrays.asList(permissions)));
            if (!decision.allowsWork(duty, namespace, permissions) && !owner)
                throw new BusinessException(ResultCode.FORBIDDEN);
        };
        if (lease == null) check.accept(reader.read(user)); else lease.require(user, check);
    }

    public void requirePublishedPlatformDuty(long user, String duty, String namespace, String permission) {
        java.util.function.Consumer<Decision> check = decision -> {
            if (decision.allowsWork(duty, namespace, permission)) return;
            if (!reader.platformCapabilityReady(duty, namespace, permission, decision))
                throw RbacProblems.problem(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                        "authorization-capability-not-ready");
            throw new BusinessException(ResultCode.FORBIDDEN);
        };
        if (lease == null) check.accept(reader.read(user)); else lease.require(user, check);
    }

    public Decision current(long user) {
        return reader.read(user);
    }

    public void requireActivityRegistrationRead(long user) {
        java.util.function.Consumer<Decision> check = decision -> {
            String permission = "activity:registration:read";
            String namespace = "ACTIVITY";
            if (!decision.allowsWork("ACTIVITY_REGISTRATION_READ", namespace, permission)
                    && !decision.allowsWork("ACTIVITY_REGISTRATION_EXPORT", namespace, permission)) {
                throw new BusinessException(ResultCode.FORBIDDEN);
            }
        };
        if (lease == null) check.accept(reader.read(user)); else lease.require(user, check);
    }

    public void requireLegacyOnly(long user, String legacyPermission) {
        throw RbacProblems.problem(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "authorization-capability-not-ready");
    }

    public void require(long user, String legacyPermission, String namespace, String... permissions) {
        if (lease == null) check(reader.read(user), legacyPermission, namespace, permissions);
        else lease.require(user, d -> check(d, legacyPermission, namespace, permissions));
    }

    /**
     * Retired ordinary-token management paths always deny; management requires independent Admin endpoints.
     */
    public void requireLegacyEntry(long user, String legacyPermission, String namespace, String... permissions) {
        throw new BusinessException(ResultCode.FORBIDDEN, "Use the independent Admin management endpoint");
    }

    private void check(Decision decision, String legacyPermission, String namespace, String[] permissions) {
        // Article operations use ArticleAuthorization; generic content/review/disposal entry points stay closed.
        if (decision.source() == Source.ASSIGNMENT && "ARTICLE".equals(namespace))
            throw new BusinessException(ResultCode.FORBIDDEN);
        boolean allowed = decision.accountEligible() && decision.source() == Source.ASSIGNMENT
                && decision.allowsAll(namespace, permissions);
        if (!allowed) throw new BusinessException(ResultCode.FORBIDDEN);
    }

    /**
     * Sa candidates are prefilters only; aliases never enter Decision.grants or authorize resources.
     */
    public List<String> candidates(Decision d) {
        if (!d.canUseAdmin()) return List.of();
        if (d.source() != Source.ASSIGNMENT) return List.of();
        Set<String> values = new TreeSet<>();
        d.grants().forEach(g -> values.addAll(g.permissionCodes()));
        d.ownerCapabilities().stream().filter(c -> d.owns(c.rootAssignmentId())).forEach(c -> values.addAll(c.permissionCodes()));
        if (values.contains("official-content:read")) values.add("content:read");
        if (d.allows("media:write", "ACTIVITY")) values.add("activity:edit");
        if (d.allows("media:write", "PUBLIC_EVENT")) values.add("public-event:edit");
        if (values.stream().anyMatch(p -> p.startsWith("activity:") && !p.endsWith(":read") && !p.contains(":registration:")))
            values.add("activity:edit");
        if (values.stream().anyMatch(p -> p.startsWith("public-event:") && !p.endsWith(":read")))
            values.add("public-event:edit");
        return List.copyOf(values);
    }

    public static String namespace(cn.jualn.miniapp.common.enums.TargetType type) {
        if (type == null) throw new BusinessException(ResultCode.FORBIDDEN);
        return switch (type) {
            case POST -> "POST";
            case ACTIVITY -> "ACTIVITY";
            case EXAM -> "PUBLIC_EVENT";
            case OFFICIAL_CONTENT -> "OFFICIAL_CONTENT";
            case ARTICLE -> "ARTICLE";
            default -> throw new BusinessException(ResultCode.FORBIDDEN);
        };
    }
}
