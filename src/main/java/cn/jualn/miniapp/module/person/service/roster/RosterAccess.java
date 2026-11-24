package cn.jualn.miniapp.module.person.service.roster;

import cn.jualn.miniapp.module.authorization.model.RbacModels.Decision;
import cn.jualn.miniapp.module.authorization.model.RbacModels.Grant;
import cn.jualn.miniapp.module.authorization.service.TransactionalAuthorization;
import cn.jualn.miniapp.module.person.service.access.PersonAuthentication;

import cn.jualn.miniapp.module.person.entity.QualificationRows;
import cn.jualn.miniapp.module.person.mapper.PersonMapper;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import static cn.jualn.miniapp.module.person.service.support.PersonnelValues.conflict;
import static cn.jualn.miniapp.module.person.service.support.PersonnelValues.problem;
import static cn.jualn.miniapp.module.person.service.support.PersonnelValues.unavailable;

@Service @RequiredArgsConstructor
public class RosterAccess {
    public static final String DUTY = "PARTY_ORG_PERSONNEL_MANAGE";
    private static final String NAMESPACE = "ORGANIZATION";
    private static final String PERMISSION = "party-personnel:manage";
    static final List<String> PARTY_ORGANIZATION_TYPES = List.of("PARTY_COMMITTEE", "PARTY_GENERAL_BRANCH", "PARTY_BRANCH");

    private final PersonAuthentication authentication;
    private final TransactionalAuthorization authorization;
    private final PersonMapper mapper;
    private final cn.jualn.miniapp.module.person.mapper.ManagedPersonMapper persons;
    public static final java.util.Map<String,String> OPERATIONS = java.util.Map.of(
            "READ", "party-personnel:read", "ADD", "party-personnel:add",
            "MAINTAIN_PROFILE", "party-personnel:profile-maintain", "REMOVE", "party-personnel:remove",
            "VERIFY_ROSTER", "party-personnel:roster-verify", "MANAGE_BINDING", "party-personnel:binding-manage");
    void capabilities(long actor,boolean write,LocalDateTime at) {
        authentication.verifyActor(actor);
        var d = authorization.current(actor, at);
        if (!d.accountEligible() || d.source() != cn.jualn.miniapp.module.authorization.model.RbacModels.Source.ASSIGNMENT
                || java.util.stream.Stream.concat(java.util.stream.Stream.of(PERMISSION), OPERATIONS.entrySet().stream()
                .filter(e -> !write || !"READ".equals(e.getKey())).map(java.util.Map.Entry::getValue))
                .noneMatch(p -> d.managementOwner(NAMESPACE,p) != null || d.grants().stream().anyMatch(g -> g.allows(p,NAMESPACE) && g.businessTarget()!=null)))
            throw problem(HttpStatus.FORBIDDEN,"forbidden");
    }
    public boolean allows(long actor, String org, String operation, LocalDateTime at) {
        var row=mapper.organization(org);
        if(row==null || !activePartyType(row.getStatus(),row.getType())) return false;
        var d=authorization.current(actor,at);
        if(!d.accountEligible() || d.source()!=cn.jualn.miniapp.module.authorization.model.RbacModels.Source.ASSIGNMENT) return false;
        var permission=OPERATIONS.get(operation);
        if(permission==null) throw new IllegalArgumentException("Unknown party operation");
        return allows(d,org,permission) || !"MANAGE_BINDING".equals(operation) && allows(d,org,PERMISSION);
    }
    private static boolean allows(Decision d,String org,String permission) {
        return d.managementOwner(NAMESPACE,permission)!=null || d.grants().stream().anyMatch(g ->
                g.allows(permission,NAMESPACE) && g.businessTarget()!=null && ("PLATFORM".equals(g.businessTarget().type())
                || "ORGANIZATION".equals(g.businessTarget().type()) && org.equals(g.businessTarget().organizationId())));
    }
    public void requireOperation(long actor,String org,String operation,LocalDateTime at) {
        authentication.verifyActor(actor);
        if(!allows(actor,org,operation,at)) throw problem(HttpStatus.FORBIDDEN,"forbidden");
    }
    public List<String> operations(long actor,String org,LocalDateTime at) {
        return OPERATIONS.keySet().stream().filter(op -> allows(actor,org,op,at)).sorted().toList();
    }
    void requireImport(long actor,String org,LocalDateTime at) {
        authentication.verifyActor(actor);
        if(java.util.stream.Stream.of("ADD","MAINTAIN_PROFILE","VERIFY_ROSTER").noneMatch(op -> allows(actor,org,op,at)))
            throw problem(HttpStatus.FORBIDDEN,"forbidden");
    }
    public void requireBinding(long actor,String org,String person,LocalDateTime at) {
        requireOperation(actor,org,"MANAGE_BINDING",at);
        if(mapper.partyMembers(new PersonMapper.PartyMembers(org,at,null,null,person,1,0)).isEmpty())
            throw problem(HttpStatus.NOT_FOUND,"not-found");
        if(persons.everBound(person,actor)) throw problem(HttpStatus.FORBIDDEN,"person-self-governance-forbidden");
    }
    boolean organization(long actor,String org,boolean write,LocalDateTime at) {
        var row = mapper.organization(org);
        if (row == null || !activePartyType(row.getStatus(), row.getType())) return false;
        var decision = authorization.current(actor, at);
        return (platform(decision) || organizationIds(decision).contains(org))
                && (!write || OPERATIONS.keySet().stream().filter(op -> !"READ".equals(op)).anyMatch(op -> allows(actor,org,op,at)));
    }

    public boolean canRead(long actor, String organization, LocalDateTime at) {
        if (!organization(actor, organization, false, at)) return false;
        capabilities(actor, false, at);
        return true;
    }

    public boolean canManage(long actor,String organization,LocalDateTime at) {
        var row=mapper.organization(organization);
        if(row==null || !activePartyType(row.getStatus(),row.getType())) return false;
        var d=authorization.current(actor,at);
        return d.accountEligible() && d.source()==cn.jualn.miniapp.module.authorization.model.RbacModels.Source.ASSIGNMENT && allows(d,organization,PERMISSION);
    }

    public static boolean activePartyType(String status, String type) {
        return "ACTIVE".equals(status) && type != null && PARTY_ORGANIZATION_TYPES.contains(type);
    }

    static boolean platform(Decision decision) {
        return java.util.stream.Stream.concat(java.util.stream.Stream.of(PERMISSION),OPERATIONS.values().stream()).anyMatch(p -> decision.managementOwner(NAMESPACE,p)!=null)
                || decision.grants().stream().filter(RosterAccess::personnelGrant)
                .anyMatch(grant -> "PLATFORM".equals(grant.businessTarget().type()));
    }

    boolean canAddElsewhere(long actor,String source,String person,LocalDateTime at) {
        if(persons.everBound(person,actor)) return false;
        var d=authorization.current(actor,at);
        if(allows(d,"",OPERATIONS.get("ADD")) || allows(d,"",PERMISSION)) {
            return mapper.partyOrganizationCount(new PersonMapper.PartyOrganizations(true,List.of(),PARTY_ORGANIZATION_TYPES,null,null,1,0))>1;
        }
        return organizationIds(d).stream().anyMatch(org -> !source.equals(org) && allows(actor,org,"ADD",at));
    }

    boolean coversRecordedPersonnel(long actor,String person,LocalDateTime at) {
        if(mapper.corruptPerson(person)!=0) return false;
        var head=mapper.head(person);
        if(head!=null && "DISPUTED".equals(head.getMembershipVerification())) return false;
        var organizations=mapper.memberships(person).stream().filter(row -> !row.getValidFrom().isAfter(at)
                && (row.getValidUntil()==null || row.getValidUntil().isAfter(at)))
                .map(row->row.getOrganizationId()).distinct().toList();
        // This proves only the recorded business relationships, never personal completeness.
        return coversSharedPersonnel(actor,organizations,at);
    }

    boolean coversSharedPersonnel(long actor, List<String> organizations, LocalDateTime at) {
        if (organizations.isEmpty()) return false;
        var decision = authorization.current(actor, at);
        if (allows(decision,"",OPERATIONS.get("MAINTAIN_PROFILE")) || allows(decision,"",PERMISSION)) {
            return organizations.stream().allMatch(id -> {
                var row = mapper.organization(id);
                return row != null && activePartyType(row.getStatus(), row.getType());
            });
        }
        return organizations.size() == 1 && allows(actor,organizations.get(0),"MAINTAIN_PROFILE",at);
    }

    static List<String> organizationIds(Decision decision) {
        return decision.grants().stream().filter(RosterAccess::personnelGrant).map(Grant::businessTarget)
                .filter(target -> "ORGANIZATION".equals(target.type()))
                .map(target -> target.organizationId()).distinct().toList();
    }

    private static boolean personnelGrant(Grant grant) {
        return grant.businessTarget() != null && (DUTY.equals(grant.dutyCode()) || grant.dutyCode()!=null && grant.dutyCode().startsWith("PARTY_PERSONNEL_"))
                && (grant.allows(PERMISSION,NAMESPACE) || OPERATIONS.values().stream().anyMatch(p -> grant.allows(p,NAMESPACE)));
    }
    boolean entry(long actor,QualificationRows.Entry row,boolean write,LocalDateTime at) {
        if(!organization(actor,row.getOrganizationId(),write,at)) return false;
        return personWithinOrganization(actor,row,write,at);
    }
    boolean personWithinOrganization(long actor,QualificationRows.Entry row,boolean write,LocalDateTime at) {
        if(row.getPersonId()==null || persons.profile(row.getPersonId())==null) throw unavailable();
        if(write && persons.everBound(row.getPersonId(),actor)) throw problem(HttpStatus.FORBIDDEN,"person-self-governance-forbidden");
        return true;
    }    void requireOrganization(long actor,String org,boolean write,LocalDateTime at) {
        capabilities(actor,write,at);if(!organization(actor,org,write,at)) throw problem(HttpStatus.NOT_FOUND,"not-found");
    }
    void active(String org) {var row=mapper.organization(org);if(row==null || !activePartyType(row.getStatus(),row.getType())) throw conflict();}
}
