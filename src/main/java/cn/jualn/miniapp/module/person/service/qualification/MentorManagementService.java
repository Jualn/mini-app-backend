package cn.jualn.miniapp.module.person.service.qualification;

import cn.jualn.miniapp.module.person.bo.MentorWorkflowBO;
import cn.jualn.miniapp.module.person.service.facts.PersonReadContext;
import cn.jualn.miniapp.module.person.service.facts.PersonFactReaders;
import cn.jualn.miniapp.module.person.service.access.PersonAuthentication;
import cn.jualn.miniapp.module.person.service.roster.PartyRosterService;
import cn.jualn.miniapp.module.person.service.support.PersonnelJournal;
import cn.jualn.miniapp.module.person.service.support.PersonnelValues;

import org.springframework.transaction.annotation.Isolation;
import cn.jualn.miniapp.module.person.bo.QualificationBO.Answer;
import cn.jualn.miniapp.module.person.bo.QualificationBO.Audit;
import cn.jualn.miniapp.module.person.bo.QualificationBO.CertifiedMentor;
import cn.jualn.miniapp.module.person.bo.QualificationBO.Mentor;
import cn.jualn.miniapp.module.person.bo.QualificationBO.Page;
import cn.jualn.miniapp.module.person.bo.QualificationBO.ProfileWrite;
import cn.jualn.miniapp.module.person.bo.QualificationBO.QualificationChange;
import cn.jualn.miniapp.module.person.bo.QualificationBO.Resource;

import cn.jualn.miniapp.module.person.entity.QualificationRows;
import cn.jualn.miniapp.module.person.mapper.ManagedPersonMapper;
import cn.jualn.miniapp.module.person.mapper.PersonMapper;
import cn.jualn.miniapp.module.person.mapper.QualificationMapper;

import cn.jualn.miniapp.module.authorization.service.RbacCatalog;
import cn.jualn.miniapp.module.authorization.service.TransactionalAuthorization;

import java.time.LocalDateTime;

import java.util.Set;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static cn.jualn.miniapp.module.person.service.support.PersonnelValues.conflict;
import static cn.jualn.miniapp.module.person.service.support.PersonnelValues.id;
import static cn.jualn.miniapp.module.person.service.support.PersonnelValues.instant;
import static cn.jualn.miniapp.module.person.service.support.PersonnelValues.invalid;
import static cn.jualn.miniapp.module.person.service.support.PersonnelValues.key;
import static cn.jualn.miniapp.module.person.service.support.PersonnelValues.match;
import static cn.jualn.miniapp.module.person.service.support.PersonnelValues.one;
import static cn.jualn.miniapp.module.person.service.support.PersonnelValues.page;
import static cn.jualn.miniapp.module.person.service.support.PersonnelValues.problem;
import static cn.jualn.miniapp.module.person.service.support.PersonnelValues.reason;
import static cn.jualn.miniapp.module.person.service.support.PersonnelValues.text;
import static cn.jualn.miniapp.module.person.service.support.PersonnelValues.unavailable;
import static cn.jualn.miniapp.module.person.service.support.PersonnelValues.utc;

/** Mentor facts belong to Person. User is only the operator or the current public account projection. */
@Service @RequiredArgsConstructor
@Transactional(isolation=Isolation.REPEATABLE_READ)
public class MentorManagementService {
    private final QualificationMapper mapper;
    private final PersonMapper facts;
    private final ManagedPersonMapper profiles;
    private final PersonFactReaders readers;
    private final PersonAuthentication authentication;
    private final TransactionalAuthorization accounts;
    private final PersonnelJournal journal;
    private final PersistentMentorQualification qualification;
    private final RbacCatalog catalog;
    private final MentorOperationAccess mentorAccess;

    private void authorized(long actor,LocalDateTime at) {
        if(!catalog.supports("MENTOR",Set.of("mentor:qualification-manage"))) throw unavailable();
        if(!accounts.permissionReady("mentor:qualification-manage")) throw unavailable();
        authentication.require(actor,"MENTOR","mentor:qualification-manage",at);
    }
    private void writable(long actor,String person,boolean active,LocalDateTime at) {
        mentorAccess.readable(actor,person,at);var profile=profiles.profile(person);if(profile==null) throw problem(org.springframework.http.HttpStatus.NOT_FOUND,"not-found");
        if(profiles.everBound(person,actor)) throw problem(org.springframework.http.HttpStatus.FORBIDDEN,"person-self-governance-forbidden");
        if(active && !"ACTIVE".equals(profile.getStatus())) throw problem(org.springframework.http.HttpStatus.CONFLICT,"person-archived");
    }
    public Page<Mentor> list(long actor,int page,int size,String q,String status) { return list(actor,page,size,q,status,null); }
    public Page<Mentor> list(long actor,int page,int size,String q,String status,String bindingState) {
        page(page,size);if(q!=null) text(q,100,"q");
        if(status!=null && !Set.of("NOT_GRANTED","NOT_YET_VALID","ACTIVE","EXPIRED","REVOKED").contains(status)) throw invalid("qualificationStatus");
        var c=readers.open(actor);var visible=mentorAccess.visible(actor,c.evaluatedAt());
        if(bindingState!=null && !Set.of("BOUND","UNBOUND").contains(bindingState)) throw invalid("bindingState");
        String search=q==null?null:q.strip();
        if(mapper.mentorListCorruption(search,status,bindingState,c.evaluatedAt(),visible)!=0) throw unavailable();
        var values=mapper.mentorPage(search,status,bindingState,c.evaluatedAt(),visible,size,(long)(page-1)*size).stream().map(row->withOperations(actor,projectList(row,c.evaluatedAt()),c.evaluatedAt())).toList();
        return new Page<>(values,page,size,mapper.mentorCount(search,status,bindingState,c.evaluatedAt(),visible),instant(c.evaluatedAt()));
    }
    public Resource<Mentor> get(long actor,String person) {
        var c=readers.open(actor);mentorAccess.readable(actor,person,c.evaluatedAt());var row=row(person);
        return cn.jualn.miniapp.module.person.service.support.PersonnelReads.resource(withOperations(actor,project(row,c.evaluatedAt()),c.evaluatedAt()),row.getRevision(),200,null);
    }
    public Resource<Mentor> put(long actor,String person,ProfileWrite input,String key,String match,String none) {
        var context=readers.open(cn.jualn.miniapp.module.person.service.access.PersonAuthorityGate.Access.EXCLUSIVE,actor);
        return putWithin(actor,person,input,key,match,none,context);
    }
    public Resource<Mentor> createWithin(long actor,String person,MentorWorkflowBO.Create input,String key,
                                        PersonReadContext c) {
        return writeWithin(actor,person,new ProfileWrite(input.position(),input.affiliation(),input.reason(),input.verificationReference()),key,null,"*",c,
                "createMentorWithPerson","MENTOR_DIRECTORY",input);
    }
    public Resource<Mentor> putWithin(long actor,String person,ProfileWrite input,String key,String match,String none,
                                     PersonReadContext c) {
        return writeWithin(actor,person,input,key,match,none,c,"putManagedMentorProfile",person,null);
    }
    private Resource<Mentor> writeWithin(long actor,String person,ProfileWrite input,String key,String match,String none,
                                         PersonReadContext c,String operation,String target,Object externalCommand) {
        c.requireUser(actor);
        if(input==null) throw invalid("body");reason(input.reason(),input.verificationReference());
        var command=new ProfileWrite(text(input.position(),200,"position"),text(input.affiliation(),200,"affiliation"),input.reason(),input.verificationReference());
        mentorAccess.writable(actor,none!=null?"mentor:create":"mentor:profile-maintain",person,c.evaluatedAt());
        var replay=journal.replay(actor,operation,target,key,externalCommand==null?command:externalCommand,Mentor.class);if(replay!=null) return replay;
        if(!"ACTIVE".equals(profiles.profile(person).getStatus())) throw problem(org.springframework.http.HttpStatus.CONFLICT,"person-archived");
        var row=mapper.mentor(person);boolean create=row==null;
        if(match==null && none==null) throw cn.jualn.miniapp.common.exception.ContractProblemException.preconditionRequired();
        if(match!=null && none!=null || none!=null && !"*".equals(none)) throw invalid("If-None-Match");
        if(create) {if(none==null) throw cn.jualn.miniapp.common.exception.ContractProblemException.preconditionFailed();row=new QualificationRows.Mentor();row.setPersonId(person);}
        else {if(none!=null) throw cn.jualn.miniapp.common.exception.ContractProblemException.preconditionFailed();match(match,row.getRevision());}
        var before=create?null:project(row,c.evaluatedAt());row.setPosition(command.position());row.setAffiliation(command.affiliation());
        row.setRevision(row.getRevision()+1);row.setLastEventId(id());
        var result=journal.append(actor,operation,target,key,externalCommand==null?command:externalCommand,row.getLastEventId(),"MENTOR",row.getRevision(),before,project(row,c.evaluatedAt()),
            command.reason(),command.verificationReference(),"ADMIN_MANUAL",command.verificationReference(),row.getRevision(),actor,c.evaluatedAt(),create?201:200,create?"/v1/admin/mentors/"+person:null);
        one(create?mapper.insertMentor(row):mapper.saveMentor(row));
        finalCheck(() -> mentorAccess.writable(actor,create?"mentor:create":"mentor:profile-maintain",person,facts.now()));return result;
    }
    public Resource<Mentor> change(long actor,String person,QualificationChange command,String key,String match) {
        if(command==null || command.action()==null || !Set.of("APPROVE","REVOKE").contains(command.action())) throw invalid("action");
        reason(command.reason(),command.verificationReference());if("REVOKE".equals(command.action()) && command.validUntil()!=null) throw invalid("validUntil");
        var c=readers.open(cn.jualn.miniapp.module.person.service.access.PersonAuthorityGate.Access.EXCLUSIVE,actor);writable(actor,person,"APPROVE".equals(command.action()),c.evaluatedAt());
        mentorAccess.require(actor,"mentor:qualification-decide",person,c.evaluatedAt());
        var replay=journal.replay(actor,"changeMentorQualification",person,key,command,Mentor.class);if(replay!=null) return replay;
        var row=row(person);match(match,row.getRevision());
        if(command.validUntil()!=null && (!command.validUntil().isAfter(instant(c.evaluatedAt())) || command.validUntil().getNano()%1000!=0)) throw invalid("validUntil");
        if("REVOKE".equals(command.action()) && (row.getQualificationId()==null || "REVOKED".equals(row.getQualificationState()))) throw conflict();
        var before=project(row,c.evaluatedAt());row.setRevision(row.getRevision()+1);row.setLastEventId(id());
        if("APPROVE".equals(command.action())) {row.setQualificationId(row.getLastEventId());row.setQualificationState("APPROVED");row.setValidFrom(c.evaluatedAt());row.setValidUntil(utc(command.validUntil()));row.setVerifiedAt(c.evaluatedAt());row.setVerifiedBy(actor);row.setVerificationReference(command.verificationReference());}
        else row.setQualificationState("REVOKED");
        var result=journal.append(actor,"changeMentorQualification",person,key,command,row.getLastEventId(),"MENTOR",row.getRevision(),before,project(row,c.evaluatedAt()),
            command.reason(),command.verificationReference(),"ADMIN_VERIFIED_SOURCE",command.verificationReference(),row.getRevision(),actor,c.evaluatedAt(),200,null);
        one(mapper.saveMentor(row));
        finalCheck(() -> { var at=facts.now();mentorAccess.require(actor,"mentor:qualification-decide",person,at);writable(actor,person,"APPROVE".equals(command.action()),at);
            if(command.validUntil()!=null && !command.validUntil().isAfter(instant(at))) throw conflict(); });return result;
    }
    public Page<Audit> history(long actor,String person,int page,int size) {
        page(page,size);var c=readers.open(actor);mentorAccess.readable(actor,person,c.evaluatedAt());row(person);return cn.jualn.miniapp.module.person.service.support.PersonnelReads.historyPage(mapper,"MENTOR",person,page,size,c.evaluatedAt());
    }
    public CertifiedMentor certified(long viewer,long user) {
        var c=readers.open(viewer,user);var decision=qualification.decide(user,c.evaluatedAt());
        if(decision.state()==cn.jualn.miniapp.module.post.service.MentorQualification.State.UNAVAILABLE) throw unavailable();
        if(!decision.valid()) throw problem(org.springframework.http.HttpStatus.NOT_FOUND,"not-found");
        var binding=profiles.accountBinding(user);if(binding==null) throw unavailable();var row=row(binding.getPersonId());var profile=profiles.profile(row.getPersonId());
        return new CertifiedMentor(Long.toString(user),profile.getRealName(),row.getPosition(),row.getAffiliation(),true,instant(c.evaluatedAt()),instant(row.getValidUntil()),row.getPersonId(),binding.getId(),row.getQualificationId());
    }
    public Page<Answer> answers(long actor,String person,int page,int size) { return answers(actor,person,page,size,null); }
    public Page<Answer> answers(long actor,String person,int page,int size,Long author) {
        page(page,size);var c=readers.open(actor);mentorAccess.readable(actor,person,c.evaluatedAt());row(person);authentication.require(actor,"POST","content:read",c.evaluatedAt());
        var decision=accounts.currentNow(actor);
        if(!decision.grants().stream().anyMatch(g->g.allows("content:read","POST") && (g.businessTarget()==null || "PLATFORM".equals(g.businessTarget().type())))
            && !decision.ownerCapabilities().stream().anyMatch(g->decision.owns(g.rootAssignmentId()) && "POST".equals(g.namespace()) && g.permissionCodes().contains("content:read"))) throw problem(org.springframework.http.HttpStatus.NOT_FOUND,"not-found");
        if(mapper.answerCorruption(person,author)!=0) throw unavailable();
        return new Page<>(mapper.answers(person,author,size,(long)(page-1)*size),page,size,mapper.answerCount(person,author),instant(c.evaluatedAt()));
    }
    private static void finalCheck(Runnable check) {
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
            new org.springframework.transaction.support.TransactionSynchronization() {
                @Override public void beforeCommit(boolean readOnly) { check.run(); }
            });
    }
    private QualificationRows.Mentor row(String person) {var row=mapper.mentor(person);if(row==null) throw problem(org.springframework.http.HttpStatus.NOT_FOUND,"not-found");if(mapper.mentorCorruption(person)!=0) throw unavailable();return row;}
    private Mentor withOperations(long actor,Mentor b,LocalDateTime at) {
        var d=mentorAccess.decision(actor,at); var operations=new java.util.ArrayList<String>(); operations.add("READ");
        boolean legacy=MentorOperationAccess.allows(d,"mentor:qualification-manage",b.personId());
        if(!profiles.everBound(b.personId(),actor)) {
            if(legacy || MentorOperationAccess.allows(d,"mentor:profile-maintain",b.personId())) operations.add("MAINTAIN_PROFILE");
            if(legacy || MentorOperationAccess.allows(d,"mentor:qualification-decide",b.personId())) operations.add("MANAGE_QUALIFICATION");
            if(MentorOperationAccess.allows(d,"mentor:binding-manage",b.personId())) operations.add("MANAGE_BINDING");
        }
        return new Mentor(b.personId(),b.realName(),b.position(),b.affiliation(),b.personStatus(),b.bindingState(),b.accountAvailability(),b.qualificationStatus(),b.qualificationId(),b.validFrom(),b.validUntil(),b.verifiedAt(),b.verifiedByUserId(),b.verificationReference(),b.version(),b.evaluatedAt(),operations);
    }
    private Mentor projectList(QualificationRows.Mentor row,LocalDateTime at) {
        if(row.getPersonStatus()==null || row.getBindingState()==null || row.getAccountAvailability()==null) throw unavailable();
        return new Mentor(row.getPersonId(),row.getRealName(),row.getPosition(),row.getAffiliation(),row.getPersonStatus(),row.getBindingState(),row.getAccountAvailability(),PersistentMentorQualification.status(row,at),row.getQualificationId(),instant(row.getValidFrom()),instant(row.getValidUntil()),instant(row.getVerifiedAt()),row.getVerifiedBy()==null?null:row.getVerifiedBy().toString(),row.getVerificationReference(),Long.toString(row.getRevision()),instant(at));
    }
    private Mentor project(QualificationRows.Mentor row,LocalDateTime at) {
        var profile=profiles.profile(row.getPersonId());if(profile==null) throw unavailable();
        var binding=profiles.personBinding(row.getPersonId());
        String availability=binding==null?"UNBOUND":accounts.current(binding.getUserId(),at).accountEligible()?"AVAILABLE":"UNAVAILABLE";
        return new Mentor(row.getPersonId(),profile.getRealName(),row.getPosition(),row.getAffiliation(),profile.getStatus(),binding==null?"UNBOUND":"BOUND",availability,PersistentMentorQualification.status(row,at),row.getQualificationId(),instant(row.getValidFrom()),instant(row.getValidUntil()),instant(row.getVerifiedAt()),row.getVerifiedBy()==null?null:row.getVerifiedBy().toString(),row.getVerificationReference(),Long.toString(row.getRevision()),instant(at));
    }
}