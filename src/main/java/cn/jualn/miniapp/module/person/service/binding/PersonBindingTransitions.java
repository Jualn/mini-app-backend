package cn.jualn.miniapp.module.person.service.binding;

import cn.jualn.miniapp.module.person.service.facts.PersonFoundationService;
import cn.jualn.miniapp.module.person.service.profile.ManagedPersonService;
import cn.jualn.miniapp.module.person.service.support.ManagedPersonJournal;
import cn.jualn.miniapp.module.person.entity.ManagedPersonRows;
import cn.jualn.miniapp.module.person.mapper.ManagedPersonMapper;
import cn.jualn.miniapp.module.admin.operation.service.AdminAuditJournal;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static cn.jualn.miniapp.module.person.service.profile.ManagedPersonService.bindingVersion;
import static cn.jualn.miniapp.module.person.service.facts.PersonFoundationService.one;
import static cn.jualn.miniapp.module.person.service.facts.PersonFoundationService.uuid;
import static cn.jualn.miniapp.module.person.PersonProblems.unavailable;
/** Internal binding transition shared by explicit binding commands and profile archival. */
@Service @RequiredArgsConstructor @Transactional(propagation=Propagation.MANDATORY)
public class PersonBindingTransitions {
    private final ManagedPersonMapper persons;
    private final ManagedPersonJournal journal;
    private final AdminAuditJournal managementAudit;
    public String end(long actor, ManagedPersonRows.Binding row, String reason, String reference, String receipt, LocalDateTime now) {
        String before = bindingVersion(row);
        row.setStatus("ENDED"); row.setRevision(Math.addExact(row.getRevision(), 1)); row.setEndedAt(now);
        row.setEndedBy(actor); row.setEndReason(reason);
        one(persons.endBinding(row)); one(persons.advancePersonSlot(row.getPersonId()));
        int changed = persons.advanceAccountSlot(row.getUserId()); if (changed != 1 && changed != 2) throw unavailable();
        String event = uuid();
        journal.event(actor, row.getPersonId(), "BINDING", row.getId(), row.getRevision(), event, "endUserPersonBinding",
                reason, reference, before, bindingVersion(row), receipt, now);
        managementAudit.duty(event,receipt,actor,"BINDING","INVALIDATE",Long.toString(row.getUserId()),row.getPersonId(),"PERSON_BINDING",row.getId(),
            managementAudit.tree(java.util.Map.of("status","ACTIVE","bindingId",row.getId())),managementAudit.tree(java.util.Map.of("status","ENDED","bindingId",row.getId())),now);
        return event;
    }

}
