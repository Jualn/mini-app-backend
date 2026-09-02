package cn.jualn.miniapp.module.admin.operation.service.impl;

import cn.jualn.miniapp.common.enums.UserRole;
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
