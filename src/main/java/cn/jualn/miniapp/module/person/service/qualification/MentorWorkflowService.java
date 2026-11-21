package cn.jualn.miniapp.module.person.service.qualification;

import cn.jualn.miniapp.module.person.service.facts.PersonFoundationService;
import cn.jualn.miniapp.module.person.PersonProblems;
import cn.jualn.miniapp.module.person.bo.MentorWorkflowBO;
import cn.jualn.miniapp.module.person.bo.QualificationBO;
import cn.jualn.miniapp.module.person.mapper.MentorWorkflowMapper;
import cn.jualn.miniapp.module.person.mapper.QualificationMapper;
import cn.jualn.miniapp.module.person.mapper.PersonMapper;
import cn.jualn.miniapp.module.person.service.access.PersonAuthorityGate;
import cn.jualn.miniapp.module.person.service.facts.PersonFactReaders;
import cn.jualn.miniapp.module.person.service.profile.ManagedPersonService;
import cn.jualn.miniapp.module.person.service.support.PersonnelJournal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import static cn.jualn.miniapp.module.person.service.support.PersonnelValues.instant;
import static cn.jualn.miniapp.module.person.service.support.PersonnelValues.page;
import static cn.jualn.miniapp.module.person.service.support.PersonnelValues.text;
import static cn.jualn.miniapp.module.person.service.support.PersonnelValues.invalid;

@Service
@RequiredArgsConstructor
@Transactional(isolation = Isolation.REPEATABLE_READ)
public class MentorWorkflowService {
    private final MentorOperationAccess access;
    private final PersonFactReaders readers;
    private final PersonMapper facts;
    private final MentorWorkflowMapper mapper;
    private final QualificationMapper mentorRecords;
    private final ManagedPersonService persons;
    private final MentorManagementService mentors;
    private final PersonnelJournal journal;

    public QualificationBO.Page<MentorWorkflowBO.Candidate> candidates(long actor, String q, int page, int size) {
        page(page,size); q=text(q,100,"q"); var c=readers.open(actor);
        // New scoped capability is required; the old qualification duty does not gain personnel search.
        var d=access.decision(actor,c.evaluatedAt());
        if(!MentorOperationAccess.allows(d,"mentor:create",null)) throw PersonProblems.denied();
        return new QualificationBO.Page<>(mapper.candidates(q,(long)(page-1)*size,size),page,size,mapper.candidateCount(q),instant(c.evaluatedAt()));
    }

    public QualificationBO.Resource<QualificationBO.Mentor> create(long actor, MentorWorkflowBO.Create input, String key) {
        if(input==null) throw invalid("body");
        var command=new MentorWorkflowBO.Create(text(input.realName(),100,"realName"),text(input.position(),200,"position"),
                text(input.affiliation(),200,"affiliation"),text(input.reason(),2000,"reason"),text(input.verificationReference(),256,"verificationReference"));
        var c=readers.open(PersonAuthorityGate.Access.EXCLUSIVE,actor);
        if(!MentorOperationAccess.allows(access.decision(actor,c.evaluatedAt()),"mentor:create",null)) throw PersonProblems.denied();
        var prior=journal.replay(actor,"createMentorWithPerson","MENTOR_DIRECTORY",key,command,QualificationBO.Mentor.class);
        if(prior!=null) return prior;
        var person=persons.createFromMentor(actor,command.realName(),command.verificationReference(),key,c);
        var result=mentors.createWithin(actor,person.personId(),command,key,c);
        PersonFoundationService.one(facts.advanceEpoch(Long.parseLong(c.authorityVersion())));
        ManagedPersonService.recheckBeforeCommit(() -> {
            if(!MentorOperationAccess.allows(access.decision(actor,facts.now()),"mentor:create",null)) throw PersonProblems.denied();
        });
        return result;
    }

    public QualificationBO.Page<MentorWorkflowBO.AccountOption> accounts(long actor,String person,String q,int page,int size) {
        page(page,size);q=text(q,100,"q");var c=readers.open(actor);
        access.writable(actor,"mentor:binding-manage",person,c.evaluatedAt());
        if(mentorRecords.mentor(person)==null) throw PersonProblems.missing();
        return new QualificationBO.Page<>(mapper.accounts(q,person,actor,(long)(page-1)*size,size),page,size,mapper.accountCount(q,person,actor),instant(c.evaluatedAt()));
    }
}
