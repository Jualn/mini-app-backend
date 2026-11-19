package cn.jualn.miniapp.module.authorization.service;

import cn.jualn.miniapp.module.authorization.mapper.RbacMapper;
import cn.jualn.miniapp.module.authorization.support.RbacProblems;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;

import static cn.jualn.miniapp.module.authorization.model.RbacModels.*;

/**
 * Current primary-DB snapshot, with authorization before any target or history lookup.
 */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.REPEATABLE_READ, readOnly = true)
public class RbacQueryService {
    public record AvailabilityBO(String lifecycle, String readiness, boolean grantable, String unavailableReason) {
    }

    public record PermissionBO(String code, String description, List<String> namespaces, AvailabilityBO availability) {
    }

    public record RoleBO(String code, String name, List<String> permissionCodes, List<String> namespaces,
                         AvailabilityBO availability) {
    }

    public record CatalogBO(String version, List<PermissionBO> permissions, List<RoleBO> roles) {
    }

    public record AssignmentPageBO(long userId, String authorizationVersion, String catalogVersion,
                                   LocalDateTime evaluatedAt,
                                   List<Assignment> items, String nextCursor) {
    }

    public record CapabilitiesBO(Decision decision, LocalDateTime refreshAfter) {
    }

    public record AuditItemBO(Change change, Assignment before, Assignment after) {
    }

    public record AuditPageBO(List<AuditItemBO> items, String nextCursor) {
    }

    public record AssignmentQuery(String status, String roleCode, String namespace, int pageSize) {
    }

    public record AuditQuery(Long actorUserId, Long targetUserId, String assignmentId, String action,
                             LocalDateTime occurredAfter, LocalDateTime occurredBefore, int pageSize) {
    }

    private final RbacMapper mapper;
    private final RbacCatalog catalog;
    private final RbacAuthorizationReader reader;
    private final RbacCursorCodec cursors;
    private final ObjectMapper json = RbacJson.mapper();

    public CatalogBO catalog(long actor) {
        Decision d = root(actor, "authorization:read");
        var ps = mapper.permissions();
        var rs = mapper.roles();
        catalog.verify(ps, rs, mapper.members());
        var pi = RbacAuthorizationReader.index(ps);
        var ri = RbacAuthorizationReader.index(rs);
        List<PermissionBO> permissions = ps.stream().map(p -> new PermissionBO(p.getCode(), catalog.permission(p.getCode()).description(),
                sorted(catalog.permission(p.getCode()).namespaces()), availability(p, catalog.permissionReady(p.getCode(), pi), p.isGrantable()))).toList();
        List<RoleBO> roles = rs.stream().map(r -> {
            var e = catalog.role(r.getCode());
            boolean ready = catalog.roleReady(e.code(), ri, pi);
            boolean grantable = r.isGrantable() && e.permissions().stream().allMatch(p -> pi.get(p).isGrantable());
            return new RoleBO(e.code(), e.name(), sorted(e.permissions()), sorted(e.namespaces()), availability(r, ready, grantable));
        }).toList();
        return new CatalogBO(d.catalogVersion(), permissions, roles);
    }

    public RoleBO role(long actor, String code) {
        return catalog(actor).roles().stream().filter(r -> r.code().equals(code)).findFirst()
                .orElseThrow(() -> RbacProblems.problem(HttpStatus.NOT_FOUND, "not-found"));
    }

    private AvailabilityBO availability(Definition d, boolean ready, boolean rawGrantable) {
        String reason = d.getUnavailableReason();
        if (reason != null && !Set.of("PROVIDER_NOT_VERIFIED", "DEFERRED", "LEGACY_ONLY", "DISABLED").contains(reason))
            throw RbacProblems.unavailable();
        boolean grantable = ready && rawGrantable;
        if (grantable) reason = null;
        else if (reason == null && !ready)
            reason = "DISABLED".equals(d.getLifecycle()) ? "DISABLED" : "PROVIDER_NOT_VERIFIED";
        return new AvailabilityBO(d.getLifecycle(), ready ? "READY" : "NOT_READY", grantable, reason);
    }

    public AssignmentPageBO assignments(long actor, long user, AssignmentQuery q, String cursor) {
        Decision d = root(actor, "authorization:read");
        Subject target = target(user);
        validate(q);
        String binding = fingerprint(List.of(actor, d.authorizationVersion(), user, target.getVersion(), d.catalogVersion(), q));
        RbacCursorCodec.Anchor a = anchor(cursor, "assignments", actor, binding, d.evaluatedAt());
        LocalDateTime evaluated = a == null ? d.evaluatedAt() : LocalDateTime.parse(a.evaluatedAt());
        List<Assignment> rows = mapper.assignmentPage(user, q.status(), q.roleCode(), q.namespace(), evaluated,
                a == null ? null : LocalDateTime.parse(a.highTime()), a == null ? null : a.highId(),
                a == null ? null : LocalDateTime.parse(a.lastTime()), a == null ? null : a.lastId(), q.pageSize() + 1);
        boolean more = rows.size() > q.pageSize();
        if (more) rows = rows.subList(0, q.pageSize());
        rows.forEach(this::assignmentFact);
        RbacOfflineAudit.verify(mapper, rows);
        String next = null;
        if (more) {
            Assignment first = rows.get(0), last = rows.get(rows.size() - 1);
            next = cursors.encode(new RbacCursorCodec.Anchor(1, "assignments", actor, binding, evaluated.toString(),
                    a == null ? first.getGrantedAt().toString() : a.highTime(), a == null ? first.getId() : a.highId(), 0,
                    last.getGrantedAt().toString(), last.getId()));
        }
        return new AssignmentPageBO(user, target.getVersion(), d.catalogVersion(), evaluated, List.copyOf(rows), next);
    }

    public long resolveUser(long actor, String user, String permission) {
        root(actor, permission);
        try {
            if (!user.matches("[1-9][0-9]*")) throw new IllegalArgumentException();
            return Long.parseLong(user);
        } catch (IllegalArgumentException e) {
            throw RbacProblems.problem(HttpStatus.NOT_FOUND, "not-found");
        }
    }

    public AssignmentPageBO assignment(long actor, long user, String id) {
        Decision d = root(actor, "authorization:read");
        Subject target = target(user);
        Assignment item = mapper.assignments(user).stream().filter(a -> a.getId().equals(id)).findFirst()
                .orElseThrow(() -> RbacProblems.problem(HttpStatus.NOT_FOUND, "not-found"));
        assignmentFact(item);
        RbacOfflineAudit.verify(mapper, item);
        return new AssignmentPageBO(user, target.getVersion(), d.catalogVersion(), d.evaluatedAt(), List.of(item), null);
    }

    public CapabilitiesBO capabilities(long actor) {
        Decision d = reader.readCurrent(actor, mapper.now());
        if (d.source() == Source.LEGACY)
            throw RbacProblems.problem(HttpStatus.CONFLICT, "authorization-source-migration-required");
        LocalDateTime refresh = d.evaluatedAt().plusSeconds(60);
        for (var grant : d.grants())
            if (grant.expiresAt() != null && grant.expiresAt().isBefore(refresh)) refresh = grant.expiresAt();
        return new CapabilitiesBO(d, refresh);
    }

    public AuditPageBO changes(long actor, AuditQuery q, String cursor) {
        Decision d = root(actor, "audit-log:read");
        if (q.pageSize() < 1 || q.pageSize() > 100 || q.action() != null && !Set.of("GRANT", "REVOKE").contains(q.action())
                || q.occurredAfter() != null && q.occurredBefore() != null && q.occurredAfter().isAfter(q.occurredBefore()))
            throw RbacProblems.invalidQuery("query");
        String binding = fingerprint(List.of(actor, d.authorizationVersion(), q));
        var a = anchor(cursor, "audit", actor, binding, d.evaluatedAt());
        long high = a == null ? mapper.auditHighWater() : a.highOrder();
        var rows = mapper.changePage(q.actorUserId(), q.targetUserId(), q.assignmentId(), q.action(), q.occurredAfter(), q.occurredBefore(),
                high, a == null ? null : LocalDateTime.parse(a.lastTime()), a == null ? null : a.lastId(), q.pageSize() + 1);
        boolean more = rows.size() > q.pageSize();
        if (more) rows = rows.subList(0, q.pageSize());
        String next = null;
        if (more) {
            Change last = rows.get(rows.size() - 1);
            next = cursors.encode(new RbacCursorCodec.Anchor(1, "audit", actor, binding, a == null ? d.evaluatedAt().toString() : a.evaluatedAt(),
                    null, null, high, last.getOccurredAt().toString(), last.getId()));
        }
        List<AuditItemBO> items = rows.stream().map(this::auditItem).toList();
        List<Assignment> snapshots = new ArrayList<>();
        for (var item : items) {
            snapshots.add(item.after());
            if (item.before() != null) snapshots.add(item.before());
        }
        RbacOfflineAudit.verify(mapper, snapshots);
        return new AuditPageBO(items, next);
    }

    private AuditItemBO auditItem(Change c) {
        try {
            if (!Integer.valueOf(1).equals(c.getSnapshotVersion()) || !Set.of("GRANT", "REVOKE").contains(c.getAction()))
                throw RbacProblems.unavailable();
            Assignment before = c.getBeforeSnapshot() == null ? null : json.readValue(c.getBeforeSnapshot(), Assignment.class);
            Assignment after = json.readValue(c.getAfterSnapshot(), Assignment.class);
            assignmentFact(after);
            if (before != null) assignmentFact(before);
            if (after == null || !Objects.equals(c.getAssignmentId(), after.getId()) || !Objects.equals(c.getTargetUserId(), after.getUserId())
                    || "REVOKE".equals(c.getAction()) && (before == null || !Objects.equals(before.getId(), after.getId()))
                    || "GRANT".equals(c.getAction()) && before != null) throw RbacProblems.unavailable();
            return new AuditItemBO(c, before, after);
        } catch (java.io.IOException e) {
            throw RbacProblems.unavailable();
        }
    }

    private Subject target(long user) {
        Subject s = mapper.subject(user);
        if (s == null || s.getDeletedAt() != null) throw RbacProblems.problem(HttpStatus.NOT_FOUND, "not-found");
        if (s.getVersion() == null || s.getVersion().isBlank() || s.getSource() == null)
            throw RbacProblems.unavailable();
        if ("LEGACY".equals(s.getSource()))
            throw RbacProblems.problem(HttpStatus.CONFLICT, "authorization-source-migration-required");
        if (!"ASSIGNMENT".equals(s.getSource())) throw RbacProblems.unavailable();
        return s;
    }

    private void assignmentFact(Assignment a) {
        if (a == null || a.getId() == null || a.getUserId() == null || a.getVersion() == null || a.getVersion().isEmpty()
                || a.getGrantedAt() == null || a.getGrantChangeId() == null || !"GLOBAL".equals(a.getScopeType())
                || catalog.role(a.getRoleCode()) == null || !catalog.role(a.getRoleCode()).namespaces().contains(a.getNamespace())
                || "ROOT".equals(a.getRoleCode()) && a.getExpiresAt() != null
                || (a.getRevokedAt() == null) != (a.getRevokeChangeId() == null)) throw RbacProblems.unavailable();
    }

    public Decision root(long actor, String permission) {
        Decision d = reader.readCurrent(actor, mapper.now());
        if (!d.accountEligible() || d.source() != Source.ASSIGNMENT || d.grants().stream()
                .noneMatch(g -> "ROOT".equals(g.roleCode()) && g.allows(permission, "AUTHORIZATION")))
            throw RbacProblems.problem(HttpStatus.FORBIDDEN, "authorization-management-forbidden");
        return d;
    }

    private void validate(AssignmentQuery q) {
        if (q.pageSize() < 1 || q.pageSize() > 100 || q.status() != null && !Set.of("ACTIVE", "EXPIRED", "REVOKED").contains(q.status())
                || q.roleCode() != null && q.roleCode().isEmpty()
                || q.namespace() != null && !catalog.namespaceKnown(q.namespace()))
            throw RbacProblems.invalidQuery("query");
    }

    private RbacCursorCodec.Anchor anchor(String value, String kind, long actor, String binding, LocalDateTime now) {
        if (value == null) return null;
        var a = cursors.decode(value);
        try {
            LocalDateTime evaluated = LocalDateTime.parse(a.evaluatedAt());
            if (!kind.equals(a.kind()) || actor != a.actor() || !binding.equals(a.binding())
                    || evaluated.isAfter(now) || evaluated.plusMinutes(15).isBefore(now))
                throw RbacCursorCodec.invalid("CURSOR_STALE");
            return a;
        } catch (java.time.format.DateTimeParseException e) {
            throw RbacCursorCodec.invalid("CURSOR_STALE");
        }
    }

    private String fingerprint(Object object) {
        try {
            return RbacCatalog.hash(json.writeValueAsString(object).getBytes(StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private List<String> sorted(Set<String> values) {
        return values.stream().sorted().toList();
    }
}
