package cn.jualn.miniapp.module.admin.operation.service;

import cn.jualn.miniapp.module.user.enums.UserRole;

public interface AdminOperationLogService {
    void recordOfficialContentStatus(long operatorId,long id,String before,String after);
    void recordPostDictionaryChange(long operatorId, String kind, long id, String before, String after);

    void recordUserRoleChange(
            Long operatorId,
            Long targetUserId,
            UserRole previousRole,
            UserRole targetRole,
            String reason);
}
