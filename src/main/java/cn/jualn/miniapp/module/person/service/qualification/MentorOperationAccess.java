package cn.jualn.miniapp.module.person.service.qualification;

import cn.jualn.miniapp.module.authorization.model.RbacModels;
import cn.jualn.miniapp.module.authorization.service.TransactionalAuthorization;
import cn.jualn.miniapp.module.person.mapper.ManagedPersonMapper;
import cn.jualn.miniapp.module.person.mapper.QualificationMapper;
import cn.jualn.miniapp.module.person.service.access.PersonAuthentication;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static cn.jualn.miniapp.module.person.service.support.PersonnelValues.problem;

/** Mentor operations carry only their own target-scoped supporting reads. */
@Service
@RequiredArgsConstructor
public class MentorOperationAccess {
    private static final Set<String> OPERATIONS = Set.of("mentor:read", "mentor:create", "mentor:profile-maintain",
            "mentor:qualification-decide", "mentor:binding-manage", "mentor:qualification-manage");
    private final TransactionalAuthorization authorization;
    private final PersonAuthentication authentication;
    private final ManagedPersonMapper persons;
    private final QualificationMapper mentors;

    public RbacModels.Decision decision(long actor, LocalDateTime at) {
        authentication.verifyActor(actor);
        return authorization.currentNow(actor);
    }

    public static boolean allows(RbacModels.Decision decision, String permission, String person) {
        if (!decision.accountEligible() || decision.source() != RbacModels.Source.ASSIGNMENT) return false;
        if (decision.managementOwner("MENTOR", permission) != null) return true;
        return decision.grants().stream().anyMatch(g -> g.namespace().equals("MENTOR") && g.permissionCodes().contains(permission)
                && g.businessTarget() != null && ("PLATFORM".equals(g.businessTarget().type())
                || person != null && "PERSON".equals(g.businessTarget().type()) && person.equals(g.businessTarget().personId())));
    }

    public void require(long actor, String permission, String person, LocalDateTime at) {
        var d = decision(actor, at);
        boolean legacy = Set.of("mentor:read", "mentor:create", "mentor:profile-maintain", "mentor:qualification-decide").contains(permission)
                && allows(d, "mentor:qualification-manage", person);
        if (!allows(d, permission, person) && !legacy) throw problem(HttpStatus.FORBIDDEN, "forbidden");
    }

    public void writable(long actor, String permission, String person, LocalDateTime at) {
        require(actor, permission, person, at);
        if (persons.profile(person) == null) throw problem(HttpStatus.NOT_FOUND, "not-found");
        if (persons.everBound(person, actor)) throw problem(HttpStatus.FORBIDDEN, "person-self-governance-forbidden");
    }

    /** Null is the platform read predicate; empty means no visible existing mentor. */
    public List<String> visible(long actor, LocalDateTime at) {
        var d = decision(actor, at);
        if (!d.accountEligible() || d.source() != RbacModels.Source.ASSIGNMENT) throw problem(HttpStatus.FORBIDDEN, "forbidden");
        if (OPERATIONS.stream().filter(p -> !p.equals("mentor:create")).anyMatch(p -> allows(d, p, null))) return null;
        var ids = new ArrayList<String>();
        for (var g : d.grants()) if (g.namespace().equals("MENTOR") && g.permissionCodes().stream().anyMatch(OPERATIONS::contains)
                && g.businessTarget() != null && "PERSON".equals(g.businessTarget().type())) ids.add(g.businessTarget().personId());
        if (allows(d, "mentor:create", null)) ids.addAll(mentors.createdMentors(actor));
        if (ids.isEmpty() && !allows(d, "mentor:create", null)
                && d.grants().stream().noneMatch(g -> g.namespace().equals("MENTOR") && g.permissionCodes().stream().anyMatch(OPERATIONS::contains)))
            throw problem(HttpStatus.FORBIDDEN, "forbidden");
        return ids.stream().distinct().toList();
    }

    public void readable(long actor, String person, LocalDateTime at) {
        var ids = visible(actor, at);
        if (ids != null && !ids.contains(person)) throw problem(HttpStatus.NOT_FOUND, "not-found");
    }
}
