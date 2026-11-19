package cn.jualn.miniapp.module.person.service.profile;

import cn.jualn.miniapp.module.person.service.facts.PersonFactReaders;
import cn.jualn.miniapp.module.person.service.facts.PersonProjection;
import cn.jualn.miniapp.module.person.service.access.PersonAuthentication;

import cn.jualn.miniapp.module.authorization.service.TransactionalAuthorization;
import cn.jualn.miniapp.module.person.mapper.ManagedPersonMapper;
import cn.jualn.miniapp.module.person.mapper.PersonMapper;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import static cn.jualn.miniapp.module.person.PersonProblems.conflict;
import static cn.jualn.miniapp.module.person.PersonProblems.denied;
import static cn.jualn.miniapp.module.person.PersonProblems.missing;
import static cn.jualn.miniapp.module.person.PersonProblems.problem;
import static cn.jualn.miniapp.module.person.PersonProblems.unavailable;

/** Independent subject access. Never submits a personId to an account reader. */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class ManagedPersonAccess {
    private final PersonAuthentication authentication;
    private final TransactionalAuthorization authorization;
    private final PersonMapper facts;
    private final ManagedPersonMapper persons;
    private final PersonFactReaders readers;

    public void capability(long actor, boolean write, LocalDateTime at) {
        authentication.require(actor, "IDENTITY", "identity:read", at);
        if (write) authentication.require(actor, "IDENTITY", "identity:map-manage", at);
    }

    public void directory(long actor,boolean write,LocalDateTime at) {
        if(!directoryPermission(actor,write,at)) throw denied();
    }
    public boolean optionalDirectoryRead(long actor,LocalDateTime at) {return optionalPermission(() -> directoryPermission(actor,false,at));}
    private boolean directoryPermission(long actor,boolean write,LocalDateTime at) {
        capability(actor,write,at);var decision=authorization.current(actor,at);
        return decision.grants().stream().anyMatch(g->decision.owns(g.assignmentId())) || decision.grants().stream().anyMatch(g->g.businessTarget()!=null
            && "PLATFORM".equals(g.businessTarget().type()) && g.allows("identity:read","IDENTITY") && (!write || g.allows("identity:map-manage","IDENTITY")));
    }
    public boolean optionalAccountRead(long actor,long user,LocalDateTime at) {
        try {authentication.require(actor,"USER","user:read",at);return true;}
        catch(cn.jualn.miniapp.common.exception.ContractProblemException failure) {
            if(failure.getStatus()==org.springframework.http.HttpStatus.FORBIDDEN || failure.getStatus()==org.springframework.http.HttpStatus.NOT_FOUND) return false;
            throw failure;
        }
    }

    public void person(long actor,String person,boolean write,LocalDateTime at) {
        if(!personPermission(actor,person,write,at,false)) throw missing();
    }
    public boolean optionalRead(long actor,String person,LocalDateTime at) {
        return optionalPermission(() -> personPermission(actor,person,false,at,true));
    }

    public boolean optionalWrite(long actor, String person, LocalDateTime at) {
        return optionalPermission(() -> personPermission(actor, person, true, at, true));
    }

    private boolean optionalPermission(java.util.function.BooleanSupplier permission) {
        try { return permission.getAsBoolean(); }
        catch (cn.jualn.miniapp.common.exception.ContractProblemException failure) {
            if (failure.getStatus() == org.springframework.http.HttpStatus.FORBIDDEN
                    || failure.getStatus() == org.springframework.http.HttpStatus.NOT_FOUND) return false;
            throw failure;
        }
    }
    private boolean personPermission(long actor,String person,boolean write,LocalDateTime at,boolean optional) {
        capability(actor, write, at);
        if (persons.profile(person) == null) throw missing();
        if (write && persons.everBound(person, actor)) {
            throw problem(org.springframework.http.HttpStatus.FORBIDDEN, "person-self-governance-forbidden");
        }
        var decision = authorization.current(actor, at);
        if (decision.grants().stream().anyMatch(g -> decision.owns(g.assignmentId()))) return true;
        if (decision.grants().stream().anyMatch(g -> g.businessTarget() != null
                && "PLATFORM".equals(g.businessTarget().type()) && g.allows("identity:read", "IDENTITY")
                && (!write || g.allows("identity:map-manage", "IDENTITY")))) return true;
        if (facts.corruptScopes(actor) != 0) throw unavailable();
        var checks = new java.util.ArrayList<PersonMapper.Check>();
        checks.add(new PersonMapper.Check("IDENTITY", "READ"));
        if (write) checks.add(new PersonMapper.Check("IDENTITY", "MAINTAIN"));
        var query = readers.query(actor, checks, at, person, null, 1, 0);
        if (facts.indeterminateCandidates(query) != 0) {if(optional) return false;throw unavailable();}
        return facts.allowed(query) == 1;
    }

    public void field(long actor, String person, String organization, boolean write, LocalDateTime at) {
        var profile = persons.profile(person);
        if (profile == null) throw missing();
        if (write && persons.everBound(person, actor)) throw problem(org.springframework.http.HttpStatus.FORBIDDEN, "person-self-governance-forbidden");
        if (write && !"ACTIVE".equals(profile.getStatus())) throw problem(org.springframework.http.HttpStatus.CONFLICT, "person-archived");
        if(organization==null) {
            capability(actor,write,at);var decision=authorization.current(actor,at);
            if(decision.grants().stream().anyMatch(g->decision.owns(g.assignmentId()))) return;
            if(decision.grants().stream().anyMatch(g->g.businessTarget()!=null && ("PLATFORM".equals(g.businessTarget().type())
                || "PERSON".equals(g.businessTarget().type()) && person.equals(g.businessTarget().personId()))
                && g.allows("identity:read","IDENTITY") && (!write || g.allows("identity:map-manage","IDENTITY")))) return;
            if(facts.corruptScopes(actor)!=0) throw unavailable();
            if(facts.actorScopes(actor).stream().anyMatch(s->"OPERATE".equals(s.getMode()) && "IDENTITY".equals(s.getDomain())
                && "PERSON".equals(s.getTargetType()) && person.equals(s.getTargetId()) && PersonFactReaders.live(s,at)
                && PersonFactReaders.actions(s).contains(cn.jualn.miniapp.module.person.bo.PersonBO.Action.READ)
                && (!write || PersonFactReaders.actions(s).contains(cn.jualn.miniapp.module.person.bo.PersonBO.Action.MAINTAIN))
                && ("ROOT_OWNER".equals(s.getSource()) || PersonFactReaders.live(facts.scope(s.getAuthorityGrantId()),at)))) return;
            throw missing();
        }
        authentication.require(actor, "ORGANIZATION", "organization:read", at);
        if (write) authentication.require(actor, "ORGANIZATION", "organization:map-manage", at);
        var org = facts.organization(organization);
        if (org == null || !"ACTIVE".equals(org.getStatus())) throw missing();
        if (!readers.rosterOrganizationTarget(actor, organization, cn.jualn.miniapp.module.person.bo.PersonBO.Domain.ORGANIZATION,
                write ? cn.jualn.miniapp.module.person.bo.PersonBO.Action.MAINTAIN : cn.jualn.miniapp.module.person.bo.PersonBO.Action.READ, at)) throw missing();
        var local = PersonProjection.localMembership(person, organization, facts.head(person), facts.localMembership(person, organization), facts.memberships(person), at);
        if (local.verification() != cn.jualn.miniapp.module.person.bo.PersonBO.Verification.VERIFIED) throw unavailable();
        if (!"PRESENT".equals(local.presence())) throw missing();
    }

    public void account(long actor, long user, LocalDateTime at) {
        authentication.require(actor, "USER", "user:read", at);
    }

    public void usable(long user, LocalDateTime at) { if (!authorization.current(user, at).accountEligible()) throw conflict(); }
}
