package cn.jualn.miniapp.module.admin.operation.service.impl;

import cn.jualn.miniapp.module.user.enums.UserRole;
import cn.jualn.miniapp.module.admin.operation.entity.AdminOperationLog;
import cn.jualn.miniapp.module.admin.operation.mapper.AdminOperationLogMapper;
import cn.jualn.miniapp.module.admin.operation.service.AdminOperationLogService;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AdminOperationLogServiceImpl implements AdminOperationLogService {

    private final AdminOperationLogMapper adminOperationLogMapper;
    private final cn.jualn.miniapp.module.admin.operation.service.AdminAuditJournal managementAudit;

    @Override
    @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void recordOfficialContentStatus(long operatorId,long id,String before,String after) {
        if(adminOperationLogMapper.insert(AdminOperationLog.builder().operatorId(operatorId).action("official-content.status.update")
            .targetType("official-content").targetId(id).reason("Explicit publication state operation")
            .beforeValue(before).afterValue(after).traceId(MDC.get("traceId")).build())!=1) throw new IllegalStateException("Official content audit insert failed");
        managementAudit.operation(java.util.UUID.randomUUID().toString(),operatorId,"PUBLISHED".equals(after)?"publishOfficialContent":"unpublishOfficialContent","OFFICIAL_CONTENT",Long.toString(id),java.time.LocalDateTime.now(java.time.ZoneOffset.UTC));
    }
    @Override
    @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void recordPostDictionaryChange(long operatorId, String kind, long id, String before, String after) {
        adminOperationLogMapper.insert(AdminOperationLog.builder().operatorId(operatorId)
                .action(kind + (before == null ? ".create" : ".update")).targetType(kind).targetId(id)
                .reason("Controlled Post dictionary maintenance")
                .beforeValue(before).afterValue(after).traceId(MDC.get("traceId")).build());
        managementAudit.operation(java.util.UUID.randomUUID().toString(),operatorId,(before==null?"create":"edit")+("post-category".equals(kind)?"PostCategory":"PostTopic"),kind,Long.toString(id),java.time.LocalDateTime.now(java.time.ZoneOffset.UTC));
    }

    @Override
    public void recordUserRoleChange(
            Long operatorId,
            Long targetUserId,
            UserRole previousRole,
            UserRole targetRole,
            String reason) {
        adminOperationLogMapper.insert(AdminOperationLog.builder()
                .operatorId(operatorId)
                .action("user.role.update")
                .targetType("user")
                .targetId(targetUserId)
                .reason(reason)
                .beforeValue(previousRole.name())
                .afterValue(targetRole.name())
                .traceId(MDC.get("traceId"))
                .build());
    }
}
