package cn.jualn.miniapp.module.audit.service;
import cn.jualn.miniapp.module.audit.mapper.ContentSafetyReviewMapper;
import cn.jualn.miniapp.infrastructure.async.outbox.OutboxService;
import cn.jualn.miniapp.infrastructure.async.job.UnknownOutcomeException;
import cn.jualn.miniapp.module.audit.async.AuditCompletedEventPayload;
import cn.jualn.miniapp.module.audit.enums.AuditScene;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.scheduling.annotation.Scheduled;
import java.time.LocalDateTime;
/** A remote attempt is never blindly resubmitted after loss of its outcome. */
@Service @RequiredArgsConstructor
public class ContentSafetyCheckExecution {
    private final ContentSafetyReviewMapper mapper;
    private final OutboxService outbox;
    private final cn.jualn.miniapp.module.audit.mapper.ContentAuditLogMapper logs;
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public long prepare(long id) {
        var check=mapper.currentAttempt(id);if(check==null) return id;
        var round=mapper.lock(check.getTaskId());
        if(ContentSafetyReviewService.terminal(round.getState())) return 0;
        check=mapper.currentAttempt(id);
        if("succeeded".equals(check.getExecutionState())) return 0;
        if("failed".equals(check.getExecutionState())) {
            // This state is only recorded before invoking the remote provider. A new attempt is safe.
            var log=cn.jualn.miniapp.module.audit.entity.ContentAuditLog.builder().targetType(round.getTargetType()).targetId(round.getTargetId())
                .reviewTaskId(round.getTaskId()).auditSource(1).finalResult(0).createdAt(LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"))).build();logs.insert(log);
            var replacement=new cn.jualn.miniapp.module.audit.entity.ContentSafetyCheck();replacement.setAuditLogId(log.getId());replacement.setTaskId(round.getTaskId());replacement.setInputReference(check.getInputReference());replacement.setSupersedesCheckId(check.getAuditLogId());mapper.insertCheck(replacement);check=replacement;
        }
        if(mapper.begin(check.getAuditLogId())==1) return check.getAuditLogId();
        if(check.getWxTraceId()!=null) return 0;
        throw new UnknownOutcomeException("Content safety check requires reconciliation",null);
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void failedBeforeInvocation(long id) {
        var round=mapper.forCheck(id);if(round==null) return;mapper.lock(round.getTaskId());
        if(mapper.failed(id)==1) outbox.append("audit.completed",1,null,"audit-log",Long.toString(id),new AuditCompletedEventPayload(id,AuditScene.fromCode(round.getTargetType()),round.getTargetId(),false,null));
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void unknown(long id) {
        var round=mapper.forCheck(id);if(round==null) return;
        mapper.lock(round.getTaskId());
        if(mapper.unknown(id)==1) outbox.append("audit.completed",1,null,"audit-log",Long.toString(id),new AuditCompletedEventPayload(id,AuditScene.fromCode(round.getTargetType()),round.getTargetId(),false,null));
    }
    @Scheduled(fixedDelayString="${app.audit.callback-watchdog-millis:60000}")
    @Transactional
    public void markMissingCallbacks() {
        // No remote resubmission: a late callback may still resolve this same attempt.
        for(var r:mapper.waiting()) if(r.getCreatedAt().isBefore(LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")).minusMinutes(15))) {
            mapper.lock(r.getTaskId());
            for(var c:mapper.checks(r.getTaskId())) if(mapper.unknown(c.getAuditLogId())==1)
                outbox.append("audit.completed",1,null,"audit-log",c.getAuditLogId().toString(),new AuditCompletedEventPayload(c.getAuditLogId(),AuditScene.fromCode(r.getTargetType()),r.getTargetId(),false,null));
        }
    }
}
