package cn.jualn.miniapp.module.admin.operation.service;
import cn.jualn.miniapp.module.admin.operation.mapper.AdminAuditMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
@Service @RequiredArgsConstructor
public class AdminAuditAttemptSink {
    private final AdminAuditJournal journal;
    private final AdminAuditMapper mapper;
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED)
    public void accept(AdminAuditAttemptContext.Attempt attempt,int httpStatus) {
        if(attempt.successEvent!=null && mapper.exists("O:"+attempt.successEvent)>0) return;
        String result=attempt.completion==org.springframework.transaction.support.TransactionSynchronization.STATUS_ROLLED_BACK?
            (httpStatus<500?"REJECTED":"FAILED"):"UNKNOWN";
        journal.attemptResult(attempt.id,attempt.actor,attempt.operation,attempt.type,attempt.target,result);
    }
}
