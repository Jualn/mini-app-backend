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
    void capabilities(long actor,boolean write,LocalDateTime at) {
        authentication.require(actor, NAMESPACE, PERMISSION, at);
    }
    boolean organization(long actor,String org,boolean write,LocalDateTime at) {
        var row = mapper.organization(org);
        if (row == null || !activePartyType(row.getStatus(), row.getType())) return false;
        var decision = authorization.current(actor, at);
        return platform(decision) || organizationIds(decision).contains(org);
    }

    public boolean canManage(long actor, String organization, LocalDateTime at) {
        if (!organization(actor, organization, false, at)) return false;
        capabilities(actor, false, at);
        return true;
    }

    public static boolean activePartyType(String status, String type) {
        return "ACTIVE".equals(status) && type != null && PARTY_ORGANIZATION_TYPES.contains(type);
    }

    static boolean platform(Decision decision) {
        return decision.managementOwner(NAMESPACE, PERMISSION) != null
                || decision.grants().stream().filter(RosterAccess::personnelGrant)
                .anyMatch(grant -> "PLATFORM".equals(grant.businessTarget().type()));
    }

    boolean coversSharedPersonnel(long actor, List<String> organizations, LocalDateTime at) {
        if (organizations.isEmpty()) return false;
        var decision = authorization.current(actor, at);
        if (platform(decision)) {
            return organizations.stream().allMatch(id -> {
                var row = mapper.organization(id);
                return row != null && activePartyType(row.getStatus(), row.getType());
            });
        }
        return organizations.size() == 1 && organization(actor, organizations.get(0), true, at);
    }

    static List<String> organizationIds(Decision decision) {
        return decision.grants().stream().filter(RosterAccess::personnelGrant).map(Grant::businessTarget)
                .filter(target -> "ORGANIZATION".equals(target.type()))
                .map(target -> target.organizationId()).distinct().toList();
    }

    private static boolean personnelGrant(Grant grant) {
        return DUTY.equals(grant.dutyCode()) && grant.businessTarget() != null && grant.allows(PERMISSION, NAMESPACE);
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
