package cn.jualn.miniapp.module.authorization.service;

import cn.jualn.miniapp.module.authorization.mapper.RbacMapper;
import cn.jualn.miniapp.module.authorization.model.RbacModels;
import cn.jualn.miniapp.module.authorization.support.RbacProblems;
import cn.jualn.miniapp.module.authorization.support.RbacTime;
import cn.jualn.miniapp.module.authorization.mapper.UnifiedMapper;

import cn.jualn.miniapp.module.person.service.access.PersonBusinessScopes;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import cn.jualn.miniapp.module.authorization.model.UnifiedModels.Selection;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

import static cn.jualn.miniapp.module.authorization.model.UnifiedModels.Binding;
import static cn.jualn.miniapp.module.authorization.model.UnifiedModels.AuthorityRow;

/**
 * Current primary snapshot dependency reader. No recursion into the RBAC decision reader.
 */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class UnifiedDutyReader {
    private final UnifiedMapper mapper;
    private final RbacMapper rbac;
    private final RbacCatalog catalog;
    private final UnifiedDutyCatalog duties;
    private final PersonBusinessScopes persons;
    private final ObjectMapper json = RbacJson.mapper();

    @Transactional(propagation = Propagation.SUPPORTS)
    public boolean dutyReady(String code, List<RbacModels.Definition> permissions, List<RbacModels.Definition> roles) {
        return duties.ready(code, permissions, roles);
    }

    public <T> T decode(String value, Class<T> type) {
        try {
            return json.readValue(value, type);
        } catch (Exception e) {
            throw RbacProblems.unavailable();
        }
    }

    public <T> List<T> list(String value, Class<T> type) {
        try {
            return json.readerForListOf(type).readValue(value);
        } catch (Exception e) {
            throw RbacProblems.unavailable();
        }
    }

    public String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw RbacProblems.unavailable();
        }
    }

    public boolean eligible(long user, LocalDateTime at) {
        var s = rbac.subject(user);
        if (s == null || !"ASSIGNMENT".equals(s.getSource())) return false;
        return RbacAuthorizationReader.accountEligible(s, at);
    }

    public RbacModels.Assignment assignment(long user, String id) {
        return rbac.assignments(user).stream().filter(a -> a.getId().equals(id)).findFirst().orElseThrow(RbacProblems::unavailable);
    }

    public boolean root(long user, String id, LocalDateTime at) {
        if (rbac.rootUsers().size() > 1) throw RbacProblems.unavailable();
        if (!eligible(user, at)) return false;
        var a = rbac.assignments(user).stream().filter(x -> id.equals(x.getId())).findFirst().orElse(null);
        return a != null && "ROOT".equals(a.getRoleCode()) && "STANDALONE".equals(a.getOriginKind())
                && "AUTHORIZATION".equals(a.getNamespace()) && "GLOBAL".equals(a.getScopeType()) && a.getExpiresAt() == null
                && a.getRevokedAt() == null && !a.getGrantedAt().isAfter(at)
                && catalog.roleReady("ROOT", RbacAuthorizationReader.index(rbac.roles()), RbacAuthorizationReader.index(rbac.permissions()));
    }

    public String authorityStatus(AuthorityRow a, LocalDateTime at) {
        if (a == null) throw RbacProblems.unavailable();
        proof(a);
        if (a.getRevokedAt() != null) return "REVOKED";
        if (!a.getExpiresAt().isAfter(at)) return "EXPIRED";
        if (a.getGrantedAt().isAfter(at) || !eligible(a.getGranteeUserId(), at) || !root(a.getGrantedBy(), a.getRootAssignmentId(), at)
                || !bindings(a.getMemberBindings(), at)) return "DEPENDENCY_ENDED";
        return "ACTIVE";
    }

    public String reason(PersonBusinessScopes.Scope s, LocalDateTime at) {
        proof(s);
        if (s.revokedAt() != null) return "SOURCE_REVOKED";
        if (!s.expiresAt().isAfter(at)) return "SOURCE_EXPIRED";
        if (!eligible(s.granteeUserId(), at)) return "ACCOUNT_BLOCKED";
        if (!bindings(s.memberBindings(), at)) return "MEMBERSHIP_ENDED";
        if (s.authorityId() != null) {
            var a = mapper.authority(s.authorityId());
            if (a == null) throw RbacProblems.unavailable();
            String status = authorityStatus(a, at);
            if (!"ACTIVE".equals(status)) return "EXPIRED".equals(status) ? "SOURCE_EXPIRED" : "SOURCE_REVOKED";
            if (a.getGranteeUserId() != s.grantedBy()) throw RbacProblems.unavailable();
        } else if (!root(s.grantedBy(), s.rootAssignmentId(), at)) return "SOURCE_REVOKED";
        var a = assignment(s.granteeUserId(), s.assignmentId());
        if (!"UNIFIED".equals(a.getOriginKind()) || !s.grantedAt().equals(a.getGrantedAt()) || !s.expiresAt().equals(a.getExpiresAt()))
            throw RbacProblems.unavailable();
        if (a.getRevokedAt() != null) return "SOURCE_REVOKED";
        var t = duties.template(s.dutyCode());
        if (!t.roleCode().equals(a.getRoleCode()) || !t.namespace().equals(a.getNamespace()) || !"GLOBAL".equals(a.getScopeType()))
            throw RbacProblems.unavailable();
        var selection = decode(s.selection(), Selection.class);
        if (selection.target() == null || !t.targetTypes().contains(selection.target().type())
                || !s.expiresAt().equals(RbacTime.expiry(selection.expiresAt()))) throw RbacProblems.unavailable();
        if (PlatformDutyPolicy.supports(s.dutyCode()) && !PlatformDutyPolicy.matches(t, selection))
            throw RbacProblems.unavailable();
        return duties.ready(s.dutyCode(), rbac.permissions(), rbac.roles()) ? "EFFECTIVE" : "NOT_READY";
    }

    public String runtimeVersion(String rbacVersion) {
        return duties.runtimeVersion(rbacVersion);
    }

    public Selection assignmentSelection(RbacModels.Assignment assignment, LocalDateTime at) {
        var scope = persons.assignmentScope(assignment.getId());
        if (scope == null) throw RbacProblems.unavailable();
        return "EFFECTIVE".equals(reason(scope, at)) ? decode(scope.selection(), Selection.class) : null;
    }

    public boolean assignmentEffective(RbacModels.Assignment assignment, LocalDateTime at) {
        var s = persons.assignmentScope(assignment.getId());
        if (s == null) throw RbacProblems.unavailable();
        return "EFFECTIVE".equals(reason(s, at));
    }

    /**
     * Existing ROOT owner source, not a synthetic duty/scope row.
     */
    public String managementOwner(RbacModels.Decision decision, String duty) {
        if (decision == null || !duties.supported(duty)) return null;
        var template = duties.template(duty);
        // This is a range/source proof, not an action permission. Each owner still checks its
        // exact action; an unavailable write must not hide an already-open read from ROOT.
        return template.permissions().stream().map(p -> decision.managementOwner(template.namespace(), p))
                .filter(Objects::nonNull).findFirst().orElse(null);
    }

    public List<PersonBusinessScopes.Scope> effective(long user, String duty, LocalDateTime at) {
        return persons.forUser(user).stream().filter(s -> duty.equals(s.dutyCode()) && "EFFECTIVE".equals(reason(s, at))).toList();
    }

    public boolean bindings(String value, LocalDateTime at) {
        for (var b : list(value, Binding.class))
            if (!persons.sameBindingCycle(b.userId(), b.personId(), b.bindingId(), b.organizationId(), b.membershipId(), at))
                return false;
        return true;
    }

    public LocalDateTime refreshBoundary(PersonBusinessScopes.Scope scope, LocalDateTime at, LocalDateTime limit) {
        var result = earlier(at, limit, scope.grantedAt(), scope.expiresAt());
        result = accountBoundary(scope.granteeUserId(), at, result);
        result = accountBoundary(scope.grantedBy(), at, result);
        for (var binding : list(scope.memberBindings(), Binding.class)) {
            result = persons.nextMembershipChange(binding.personId(), at, result);
        }
        if (scope.authorityId() != null) {
            var authority = mapper.authority(scope.authorityId());
            if (authority == null) {
                throw RbacProblems.unavailable();
            }
            result = earlier(at, result, authority.getGrantedAt(), authority.getExpiresAt());
            result = accountBoundary(authority.getGrantedBy(), at, result);
            for (var binding : list(authority.getMemberBindings(), Binding.class)) {
                result = persons.nextMembershipChange(binding.personId(), at, result);
            }
        }
        return result;
    }

    private LocalDateTime accountBoundary(long user, LocalDateTime at, LocalDateTime limit) {
        var subject = rbac.subject(user);
        if (subject == null) {
            throw RbacProblems.unavailable();
        }
        var ban = subject.getBanExpireAt();
        if (ban != null) {
            ban = ban.atZone(java.time.ZoneId.of("Asia/Shanghai")).withZoneSameInstant(java.time.ZoneOffset.UTC).toLocalDateTime();
        }
        var result = earlier(at, limit, ban);
        for (var assignment : rbac.assignments(user)) {
            result = earlier(at, result, assignment.getGrantedAt(), assignment.getExpiresAt());
        }
        return result;
    }

    private static LocalDateTime earlier(LocalDateTime at, LocalDateTime limit, LocalDateTime... candidates) {
        var result = limit;
        for (var candidate : candidates) {
            if (candidate != null && candidate.isAfter(at) && candidate.isBefore(result)) {
                result = candidate;
            }
        }
        return result;
    }

    public boolean hasAuthority(long user, LocalDateTime at) {
        return mapper.authorities().stream().filter(a -> a.getGranteeUserId() == user).anyMatch(a -> "ACTIVE".equals(authorityStatus(a, at)));
    }

    private void proof(AuthorityRow a) {
        var c = mapper.commandById(a.getChangeId());
        if (c == null || !"grantDelegationAuthority".equals(c.getOperation()) || c.getActor() != a.getGrantedBy())
            throw RbacProblems.unavailable();
        var original = decode(c.getAuditJson(), AuthorityRow.class);
        if (!Objects.equals(original.getId(), a.getId()) || original.getGranteeUserId() != a.getGranteeUserId()
                || original.getGrantedBy() != a.getGrantedBy() || !Objects.equals(original.getChangeId(), a.getChangeId()) || !Objects.equals(original.getReason(), a.getReason())
                || !Objects.equals(original.getRootAssignmentId(), a.getRootAssignmentId()) || !sameJson(original.getCeilings(), a.getCeilings())
                || !sameJson(original.getMemberBindings(), a.getMemberBindings()) || !Objects.equals(original.getRecipientRule(), a.getRecipientRule())
                || !Objects.equals(original.getExpiresAt(), a.getExpiresAt()) || !Objects.equals(original.getGrantedAt(), a.getGrantedAt()))
            throw RbacProblems.unavailable();
    }

    private void proof(PersonBusinessScopes.Scope s) {
        var c = mapper.commandById(s.changeId());
        if (c == null || !"commitDutyChange".equals(c.getOperation()) || c.getActor() != s.grantedBy())
            throw RbacProblems.unavailable();
        var original = list(c.getAuditJson(), PersonBusinessScopes.Scope.class).stream().filter(x -> x.id().equals(s.id())).findFirst().orElseThrow(RbacProblems::unavailable);
        if (original.granteeUserId() != s.granteeUserId() || original.grantedBy() != s.grantedBy() || !original.dutyCode().equals(s.dutyCode()) || !original.changeId().equals(s.changeId())
                || !s.dutyCode().equals(decode(s.selection(), Selection.class).dutyCode()) || !sameJson(original.selection(), s.selection()) || !original.assignmentId().equals(s.assignmentId())
                || !Objects.equals(original.authorityId(), s.authorityId()) || !Objects.equals(original.rootAssignmentId(), s.rootAssignmentId())
                || !sameJson(original.memberBindings(), s.memberBindings()) || !original.grantedAt().equals(s.grantedAt()) || !original.expiresAt().equals(s.expiresAt()))
            throw RbacProblems.unavailable();
    }

    private boolean sameJson(String a, String b) {
        try {
            return json.readTree(a).equals(json.readTree(b));
        } catch (Exception e) {
            throw RbacProblems.unavailable();
        }
    }
}
