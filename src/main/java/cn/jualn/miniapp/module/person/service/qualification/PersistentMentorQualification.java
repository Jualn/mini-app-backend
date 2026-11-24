package cn.jualn.miniapp.module.person.service.qualification;

import cn.jualn.miniapp.module.person.service.access.PersonAuthorityGate;
import cn.jualn.miniapp.module.person.service.support.PersonnelValues;

import org.springframework.transaction.annotation.Propagation;
import cn.jualn.miniapp.module.post.service.MentorQualification;
import cn.jualn.miniapp.module.person.mapper.ManagedPersonMapper;
import cn.jualn.miniapp.module.person.mapper.PersonMapper;
import cn.jualn.miniapp.module.person.mapper.QualificationMapper;

import cn.jualn.miniapp.module.person.entity.QualificationRows;
import cn.jualn.miniapp.module.authorization.service.TransactionalAuthorization;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import static cn.jualn.miniapp.module.person.service.support.PersonnelValues.encode;
import static cn.jualn.miniapp.module.person.service.support.PersonnelValues.unavailable;

/** Current primary facts; no badge cache, RBAC mentor role or permissive fallback. */
@Service @RequiredArgsConstructor
public class PersistentMentorQualification implements MentorQualification {
    private final QualificationMapper mapper;
    private final PersonMapper facts;
    private final ManagedPersonMapper profiles;
    private final TransactionalAuthorization accounts;
    @Override @Transactional
    public Decision evaluate(long user) {
        try {
            guardWrite(user);return decide(user,facts.now());
        } catch(DataAccessException e) {return new Decision(State.UNAVAILABLE,null);}
        catch(cn.jualn.miniapp.common.exception.ContractProblemException e) {
            return new Decision(e.getStatus()==org.springframework.http.HttpStatus.NOT_FOUND?State.INELIGIBLE:State.UNAVAILABLE,null);
        }
    }
    @Override @Transactional(propagation=Propagation.REQUIRES_NEW)
    public Decision currentForOriginal(long user,String originalPerson) {return evaluate(user);}
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public void guardPublication(long user,Long reviewer) {accounts.begin(PersonAuthorityGate.Access.SHARED,reviewer==null?new long[]{user}:new long[]{user,reviewer});}
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public void guardWrite(long user) {
        try {accounts.begin(cn.jualn.miniapp.module.person.service.access.PersonAuthorityGate.Access.SHARED,user);}
        catch(DataAccessException e) {throw sourceUnavailable();}
        catch(cn.jualn.miniapp.common.exception.ContractProblemException e) {if(e.getStatus()==org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE) throw sourceUnavailable();throw e;}
    }
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public Decision forWrite(long user) {
        guardWrite(user);final Decision decision;
        try {decision=decide(user,facts.now());} catch(DataAccessException e) {throw sourceUnavailable();}
        if(decision.valid()) TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void beforeCommit(boolean readOnly) {
                final Decision current;try {current=decide(user,facts.now());} catch(DataAccessException e) {throw sourceUnavailable();}
                if(current.state()==State.UNAVAILABLE) throw cn.jualn.miniapp.module.post.PostProblems.problem(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"mentor-qualification-unavailable","Mentor qualification cannot currently be verified");
                if(!current.valid() || !decision.evidence().equals(current.evidence()))
                    throw cn.jualn.miniapp.module.post.PostProblems.problem(org.springframework.http.HttpStatus.FORBIDDEN,"mentor-answer-forbidden","Current mentor qualification is required");
            }
        });
        return decision;
    }
    private static cn.jualn.miniapp.common.exception.ContractProblemException sourceUnavailable() {
        return cn.jualn.miniapp.module.post.PostProblems.problem(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"mentor-qualification-unavailable","Mentor qualification cannot currently be verified");
    }
    Decision decide(long user,LocalDateTime at) {
        if(facts.corruptSource()!=0) return new Decision(State.UNAVAILABLE,null);
        var binding=profiles.accountBinding(user);if(binding==null) return new Decision(State.INELIGIBLE,null);
        if (!cn.jualn.miniapp.module.person.service.binding.PersonBindingTrust.trusted(binding)) return new Decision(State.UNAVAILABLE,null);
        if ("ROSTER_EXACT_MATCH".equals(binding.getVerificationMethod())) return new Decision(State.INELIGIBLE,null);
        if (!"ADMIN_MANUAL".equals(binding.getVerificationMethod()) || profiles.confirmedAdminBinding(binding.getId())!=1) return new Decision(State.UNAVAILABLE,null);
        var profile=profiles.profile(binding.getPersonId());if(profile==null) return new Decision(State.UNAVAILABLE,null);
        if(!"ACTIVE".equals(profile.getStatus()) || !accounts.current(user,at).accountEligible()) return new Decision(State.INELIGIBLE,null);
        if(mapper.mentorCorruption(binding.getPersonId())!=0) return new Decision(State.UNAVAILABLE,null);
        var row=mapper.mentor(binding.getPersonId());if(row==null) return new Decision(State.INELIGIBLE,null);
        boolean active="ACTIVE".equals(status(row,at));
        String evidence=active?encode(java.util.Map.of("personId",binding.getPersonId(),"bindingId",binding.getId(),"qualificationId",row.getQualificationId(),"qualificationVersion",Long.toString(row.getRevision()))):null;
        if(!active)return new Decision(State.INELIGIBLE,null);
        if(row.getRealName()==null || row.getRealName().isBlank() || row.getPosition()==null || row.getPosition().isBlank() || row.getAffiliation()==null || row.getAffiliation().isBlank())return new Decision(State.UNAVAILABLE,null);
        var proof=new cn.jualn.miniapp.module.post.bo.MentorQaBO.Proof(user,binding.getPersonId(),binding.getId(),row.getQualificationId(),Long.toString(row.getRevision()),at.atOffset(java.time.ZoneOffset.UTC),new cn.jualn.miniapp.module.post.bo.MentorQaBO.Profile(row.getRealName(),row.getPosition(),row.getAffiliation()));
        return new Decision(State.VALID,evidence,proof);
    }
    static String status(QualificationRows.Mentor row,LocalDateTime at) {
        if(row.getQualificationId()==null) return "NOT_GRANTED";
        if("REVOKED".equals(row.getQualificationState())) return "REVOKED";
        if(!"APPROVED".equals(row.getQualificationState()) || row.getValidFrom()==null || row.getVerifiedAt()==null || row.getVerifiedBy()==null
            || row.getVerificationReference()==null || row.getVerificationReference().isBlank()) throw unavailable();
        if(row.getValidFrom().isAfter(at)) return "NOT_YET_VALID";
        return row.getValidUntil()!=null && !row.getValidUntil().isAfter(at)?"EXPIRED":"ACTIVE";
    }
}