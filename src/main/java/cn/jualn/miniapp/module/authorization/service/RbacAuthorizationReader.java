package cn.jualn.miniapp.module.authorization.service;

import cn.jualn.miniapp.module.authorization.mapper.RbacMapper;
import cn.jualn.miniapp.module.authorization.support.RbacProblems;
import cn.jualn.miniapp.module.authorization.model.UnifiedModels;

import cn.jualn.miniapp.module.admin.auth.support.AdminPermissionPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DataAccessException;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import cn.jualn.miniapp.module.authorization.model.RbacModels.*;

/**
 * Fresh primary-database transaction per decision; no Session or Redis positive authorization.
 */
@Service
@RequiredArgsConstructor
public class RbacAuthorizationReader {
    private final RbacMapper mapper;
    private final RbacCatalog catalog;
    private final AdminPermissionPolicy legacy;
    private UnifiedDutyReader unified;

    @Autowired
    void unified(ObjectProvider<UnifiedDutyReader> provider) {
        this.unified = provider.getIfAvailable();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.REPEATABLE_READ)
    public Decision read(long userId) {
        try {
            return readCurrent(userId, mapper.now());
        } catch (DataAccessException e) {
            throw RbacProblems.unavailable();
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.REPEATABLE_READ)
    public Decision readLegacyEntry(long userId) {
        try {
            return readCurrent(userId, mapper.now());
        } catch (DataAccessException e) {
            throw RbacProblems.unavailable();
        }
    }

    // Governance invokes within its own READ_COMMITTED transaction after taking its mutex.
    Decision readCurrent(long userId, LocalDateTime now) {
        Subject subject = mapper.subject(userId);
        if (subject == null || subject.getSource() == null || subject.getVersion() == null || subject.getVersion().isBlank())
            throw RbacProblems.unavailable();
        Source source;
        try {
            source = Source.valueOf(subject.getSource());
        } catch (IllegalArgumentException e) {
            throw RbacProblems.unavailable();
        }
        Integer status = subject.getStatus();
        if (status == null || !Set.of(1, 2, 3).contains(status)) throw RbacProblems.unavailable();
        // User restrictions use Beijing business time, independent of the JVM zone.
        // RBAC timestamps are UTC; compare restriction expiry in the User owner's existing time domain.
        boolean eligible = accountEligible(subject, now);
        if (source != Source.ASSIGNMENT) throw RbacProblems.unavailable();
        var permissionRows = mapper.permissions();
        var roleRows = mapper.roles();
        catalog.verify(permissionRows, roleRows, mapper.members());
        Map<String, Definition> permissions = index(permissionRows), roles = index(roleRows);
        List<Grant> grants = new ArrayList<>();
        var assignments = mapper.currentAssignments(userId, now).stream()
                .filter(a -> !"STANDALONE".equals(a.getOriginKind()) || "ROOT".equals(a.getRoleCode()))
                .toList();
        RbacOfflineAudit.verify(mapper, assignments);
        if (assignments.stream().anyMatch(a->"ROOT".equals(a.getRoleCode())) && mapper.rootUsers().size()!=1) throw RbacProblems.unavailable();
        for (Assignment assignment : assignments) {
            RbacCatalog.Entry role = catalog.role(assignment.getRoleCode());
            if (role == null || !role.namespaces().contains(assignment.getNamespace()) || !"GLOBAL".equals(assignment.getScopeType())
                    || assignment.getGrantedAt() == null || assignment.getVersion() == null
                    || "ROOT".equals(assignment.getRoleCode()) && assignment.getExpiresAt() != null)
                throw RbacProblems.unavailable();
            if (!eligible || !"ACTIVE".equals(assignment.status(now)) || assignment.getGrantedAt().isAfter(now)
                    || !catalog.roleReady(role.code(), roles, permissions)) continue;
            UnifiedModels.Selection selection = null;
            if ("UNIFIED".equals(assignment.getOriginKind())) {
                if (unified == null) throw RbacProblems.unavailable();
                selection = unified.assignmentSelection(assignment, now);
                if (selection == null) continue;
            } else if (!"STANDALONE".equals(assignment.getOriginKind())) throw RbacProblems.unavailable();
            List<String> codes = catalog.rolePermissions(role.code(), assignment.getNamespace()).stream()
                    .filter(p -> catalog.permissionReady(p, permissions)).sorted().toList();
            if (!codes.isEmpty())
                grants.add(new Grant(assignment.getId(), role.code(), assignment.getNamespace(), "GLOBAL", codes, assignment.getExpiresAt(), selection == null ? null : selection.dutyCode(), selection == null ? null : selection.target()));
        }
        return new Decision(userId, source, subject.getVersion(), (unified == null ? catalog.runtimeVersion(permissionRows, roleRows) : unified.runtimeVersion(catalog.runtimeVersion(permissionRows, roleRows))), now, eligible, List.of(), grants, catalog.ownerCapabilities(grants, permissions),
                eligible && unified != null && unified.hasAuthority(userId, now));
    }

    boolean platformCapabilityReady(String duty, String namespace, String permission, Decision decision) {
        try {
            var permissions = mapper.permissions();
            var roles = mapper.roles();
            catalog.verify(permissions, roles, mapper.members());
            var definitions = index(permissions);
            boolean root = decision.grants().stream().anyMatch(g -> decision.owns(g.assignmentId()));
            return root ? catalog.supports(namespace, Set.of(permission)) && catalog.permissionReady(permission, definitions)
                    : unified != null && unified.dutyReady(duty, permissions, roles);
        } catch (DataAccessException e) {
            throw RbacProblems.unavailable();
        }
    }

    static Map<String, Definition> index(List<Definition> rows) {
        return rows.stream().collect(Collectors.toMap(Definition::getCode, Function.identity()));
    }

    static boolean accountEligible(Subject subject, LocalDateTime now) {
        if (subject == null || subject.getStatus() == null || !Set.of(1, 2, 3).contains(subject.getStatus()))
            throw RbacProblems.unavailable();
        var accountNow = now.atOffset(ZoneOffset.UTC).atZoneSameInstant(ZoneId.of("Asia/Shanghai")).toLocalDateTime();
        return subject.getDeletedAt() == null && (subject.getStatus() == 1 || subject.getBanExpireAt() != null && !subject.getBanExpireAt().isAfter(accountNow));
    }
}
