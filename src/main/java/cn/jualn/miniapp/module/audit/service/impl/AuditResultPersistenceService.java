package cn.jualn.miniapp.module.audit.service.impl;

import cn.jualn.miniapp.common.enums.AuditScene;
import cn.jualn.miniapp.infrastructure.async.outbox.OutboxService;
import cn.jualn.miniapp.module.audit.async.AuditCompletedEventPayload;
import cn.jualn.miniapp.module.audit.entity.ContentAuditLog;
import cn.jualn.miniapp.module.audit.enums.AuditStatus;
import cn.jualn.miniapp.module.audit.mapper.ContentAuditLogMapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuditResultPersistenceService {
    private static final int WX_RESULT_NORMAL = 0;
    private static final int WX_RESULT_RISK = 1;

    private final ContentAuditLogMapper auditLogMapper;
    private final OutboxService outboxService;

    @Transactional(rollbackFor = Exception.class)
    public boolean completeText(long auditLogId, AuditScene scene, long targetId, String wxTraceId,
                                AuditStatus result, String wxDetail, String reason) {
        LambdaUpdateWrapper<ContentAuditLog> condition = baseCondition(auditLogId, scene, targetId);
        return updateAndAppend(condition, auditLogId, scene, targetId, wxTraceId, result, wxDetail, reason);
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean completeMedia(long auditLogId, AuditScene scene, long targetId, String wxTraceId,
                                 AuditStatus result, String wxDetail, String reason) {
        LambdaUpdateWrapper<ContentAuditLog> condition = baseCondition(auditLogId, scene, targetId)
                .eq(ContentAuditLog::getWxTraceId, wxTraceId);
        return updateAndAppend(condition, auditLogId, scene, targetId, wxTraceId, result, wxDetail, reason);
    }

    private LambdaUpdateWrapper<ContentAuditLog> baseCondition(long auditLogId, AuditScene scene, long targetId) {
        return new LambdaUpdateWrapper<ContentAuditLog>()
                .eq(ContentAuditLog::getId, auditLogId)
                .eq(ContentAuditLog::getTargetType, scene.getCode())
                .eq(ContentAuditLog::getTargetId, targetId)
                .eq(ContentAuditLog::getFinalResult, AuditStatus.PENDING.getCode());
    }

    private boolean updateAndAppend(LambdaUpdateWrapper<ContentAuditLog> condition, long auditLogId,
                                    AuditScene scene, long targetId, String wxTraceId,
                                    AuditStatus result, String wxDetail, String reason) {
        ContentAuditLog update = ContentAuditLog.builder()
                .wxTraceId(wxTraceId)
                .wxResult(result == AuditStatus.PASS ? WX_RESULT_NORMAL : WX_RESULT_RISK)
                .wxDetail(wxDetail)
                .finalResult(result.getCode())
                .build();
        if (auditLogMapper.update(update, condition) != 1) {
            return false;
        }
        outboxService.append("audit.completed", 1, MDC.get("operationId"), "audit-log",
                String.valueOf(auditLogId),
                new AuditCompletedEventPayload(auditLogId, scene, targetId,
                        result == AuditStatus.PASS, reason));
        return true;
    }
}
