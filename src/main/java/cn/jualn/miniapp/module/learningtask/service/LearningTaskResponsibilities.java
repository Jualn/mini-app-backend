package cn.jualn.miniapp.module.learningtask.service;

import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Recipient;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Subject;
import cn.jualn.miniapp.module.person.service.access.PersonBusinessScopes;
import cn.jualn.miniapp.module.user.service.UserLearningTaskRecipientService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import static cn.jualn.miniapp.module.learningtask.LearningTaskProblems.missing;

/** Resolves delivery without changing the immutable responsibility subject. Caller holds the Person gate. */
@Service
@RequiredArgsConstructor
@Transactional(propagation=Propagation.MANDATORY)
public class LearningTaskResponsibilities {
    private final PersonBusinessScopes persons;
    private final UserLearningTaskRecipientService accounts;

    public Recipient recipient(Subject subject) {
        Long user;String binding=null;
        if("PERSON".equals(subject.type())) {
            var current=persons.deliveryBinding(subject.personId());if(current==null) return new Recipient(subject,null,null,"PERSON_UNAVAILABLE");
            if(!"ACTIVE".equals(current.status())) return new Recipient(subject,current.userId(),current.bindingId(),"PERSON_UNAVAILABLE");
            if(current.userId()==null) return new Recipient(subject,null,null,"NO_BINDING");
            user=current.userId();binding=current.bindingId();
        } else if("ACCOUNT".equals(subject.type())) user=accountId(subject.userId());
        else throw missing();
        boolean available=accounts.available(List.of(user)).contains(user);
        return new Recipient(subject,user,binding,available?"AVAILABLE":"ACCOUNT_UNAVAILABLE");
    }
    public String own(long user,Subject subject) {
        var current=recipient(subject);
        if(!"AVAILABLE".equals(current.availability()) || !Long.valueOf(user).equals(current.deliveryUserId())) throw missing();
        return current.bindingId();
    }
    public String accountPerson(long user) {return persons.boundPerson(user);}
    private static long accountId(String value) {
        try {long user=Long.parseLong(value);if(user<=0 || !Long.toString(user).equals(value)) throw missing();return user;}
        catch(NumberFormatException failure) {throw missing();}
    }
}
