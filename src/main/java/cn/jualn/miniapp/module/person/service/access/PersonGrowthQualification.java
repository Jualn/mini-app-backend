package cn.jualn.miniapp.module.person.service.access;

import cn.jualn.miniapp.module.person.mapper.ManagedPersonMapper;
import cn.jualn.miniapp.module.person.mapper.PersonMapper;
import cn.jualn.miniapp.module.person.entity.PersonRows;
import cn.jualn.miniapp.module.person.service.binding.PersonBindingTrust;
import cn.jualn.miniapp.module.authorization.service.TransactionalAuthorization;
import cn.jualn.miniapp.module.post.service.GrowthQualification;
import cn.jualn.miniapp.module.post.GrowthProblems;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import java.time.*;
import java.util.*;

@Service @RequiredArgsConstructor
public class PersonGrowthQualification implements GrowthQualification {
    private final PersonMapper facts;
    private final ManagedPersonMapper profiles;
    private final TransactionalAuthorization authorization;
    private static final Set<String> STAGES=Set.of("PARTY_ACTIVIST","PARTY_DEVELOPMENT_CANDIDATE","PROBATIONARY_PARTY_MEMBER","FULL_PARTY_MEMBER");

    @Override @Transactional(readOnly=true)
    public String currentPerson(long actor) {
        try {
            if(facts.corruptSource()!=0) throw GrowthProblems.source();
            var binding=profiles.accountBinding(actor);
            if(binding==null) return null;
            if(!PersonBindingTrust.trusted(binding)) throw GrowthProblems.source();
            if("ADMIN_MANUAL".equals(binding.getVerificationMethod()) && profiles.confirmedAdminBinding(binding.getId())!=1) throw GrowthProblems.source();
            var person=profiles.profile(binding.getPersonId());
            if(person==null) throw GrowthProblems.source();
            if(!"ACTIVE".equals(person.getStatus()) || facts.corruptPerson(binding.getPersonId())!=0)throw GrowthProblems.source();
            return binding.getPersonId();
        } catch(DataAccessException failure) {throw GrowthProblems.source();}
    }
    @Override @Transactional(readOnly=true)
    public Context context(long actor) {
        try {
            String person=currentPerson(actor);var at=facts.now();
            if(person==null) return new Context(null,at.atOffset(ZoneOffset.UTC),List.of());
            var types=types(facts.head(person),at);
            if(types.isEmpty()) return new Context(person,at.atOffset(ZoneOffset.UTC),List.of());
            var result=new ArrayList<Organization>();
            for(String org:facts.memberships(person).stream().map(PersonRows.Membership::getOrganizationId).distinct().sorted().toList()) {
                if(memberPerson(person,org,at)) {var row=facts.organization(org);result.add(new Organization(org,row.getName(),types));}
            }
            return new Context(person,at.atOffset(ZoneOffset.UTC),List.copyOf(result));
        } catch(DataAccessException failure) {throw GrowthProblems.source();}
    }
    @Override @Transactional(readOnly=true)
    public boolean member(long actor,String organization) {
        try {var person=currentPerson(actor);return person!=null && memberPerson(person,organization,facts.now());}
        catch(DataAccessException failure) {throw GrowthProblems.source();}
    }
    boolean memberPerson(String person,String org,LocalDateTime at) {
        var organization=facts.organization(org);
        if(organization==null || !"ACTIVE".equals(organization.getStatus()) || !"PARTY_BRANCH".equals(organization.getType()))return false;
        var head=facts.head(person);var local=facts.localMembership(person,org);
        boolean global=head!=null && "VERIFIED".equals(head.getMembershipVerification()) && head.getMembershipVerifiedBy()!=null && head.getMembershipVerifiedAt()!=null;
        return facts.memberships(person).stream().anyMatch(m->org.equals(m.getOrganizationId()) && m.getEndedEventId()==null
            && m.getValidFrom()!=null && !at.isBefore(m.getValidFrom()) && (m.getValidUntil()==null || at.isBefore(m.getValidUntil()))
            && (global || local!=null && "PRESENT".equals(local.getPresence()) && m.getId().equals(local.getMembershipId()) && local.getVerifiedAt()!=null && local.getVerifiedBy()>0));
    }
    @Override @Transactional(readOnly=true)
    public boolean organizationActive(String id){try{var org=facts.organization(id);return org!=null && "ACTIVE".equals(org.getStatus()) && "PARTY_BRANCH".equals(org.getType());}catch(DataAccessException failure){throw GrowthProblems.source();}}
    static List<String> types(PersonRows.Head head,LocalDateTime at) {
        if(head==null || !"VERIFIED".equals(head.getIdentityVerification()) || !"RECORDED".equals(head.getIdentityPresence())
            || !"ACTIVE".equals(head.getIdentityStatus()) || head.getIdentityVerifiedAt()==null || head.getIdentityVerifiedBy()==null
            || head.getIdentityValidFrom()==null || at.isBefore(head.getIdentityValidFrom()) || head.getIdentityValidUntil()!=null && !at.isBefore(head.getIdentityValidUntil())
            || !STAGES.contains(Objects.toString(head.getStageId(),""))) return List.of();
        return Set.of("PROBATIONARY_PARTY_MEMBER","FULL_PARTY_MEMBER").contains(head.getStageId())?List.of("GROWTH","POLITICAL_BIRTHDAY"):List.of("GROWTH");
    }
    @Override @Transactional(readOnly=true)
    public String requirePublisher(long actor,String org,String type) {
        var context=context(actor);
        if(context.personId()==null || context.organizations().stream().noneMatch(o->o.organizationId().equals(org) && o.allowedRecordTypes().contains(type))) throw GrowthProblems.forbidden();
        return context.personId();
    }
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public void guard(long actor) {
        try {authorization.begin(PersonAuthorityGate.Access.SHARED,actor);}
        catch(DataAccessException failure) {throw GrowthProblems.source();}
    }
}
