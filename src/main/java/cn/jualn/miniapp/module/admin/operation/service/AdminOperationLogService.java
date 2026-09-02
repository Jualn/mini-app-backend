package cn.jualn.miniapp.module.admin.operation.service;

import cn.jualn.miniapp.common.enums.UserRole;

public interface AdminOperationLogService {

    void recordUserRoleChange(
            Long operatorId,
            Long targetUserId,
            UserRole previousRole,
            UserRole targetRole,
            String reason);
}
