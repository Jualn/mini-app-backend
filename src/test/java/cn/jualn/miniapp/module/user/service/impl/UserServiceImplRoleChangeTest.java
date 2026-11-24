package cn.jualn.miniapp.module.user.service.impl;

import cn.jualn.miniapp.module.user.service.UserAccountService;
import cn.jualn.miniapp.module.user.enums.UserRole;
import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.module.user.bo.AdminUserRoleChangeBO;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserServiceImplRoleChangeTest {
    @ParameterizedTest
    @EnumSource(UserRole.class)
    void everyLegacyRoleWriteIsFrozenBeforeAnyPersistence(UserRole role) {
        var profiles=mock(cn.jualn.miniapp.module.user.mapper.UserProfileMapper.class);
        var authorities=mock(cn.jualn.miniapp.module.authorization.service.AuthorizationAuthorityService.class);
        var guard=mock(cn.jualn.miniapp.module.authorization.service.RbacGovernanceService.class);
        var authorization=mock(cn.jualn.miniapp.module.authorization.service.CurrentAuthorization.class);
        var audit=mock(cn.jualn.miniapp.module.admin.operation.service.AdminOperationLogService.class);
        var cache=mock(cn.jualn.miniapp.infrastructure.cache.RedisService.class);
        var service=new UserAccountService(profiles,cache,guard,authorization,org.mockito.Mockito.mock(cn.jualn.miniapp.module.admin.operation.service.AdminAuditJournal.class));
        for(long target:new long[]{10,20}) {
            var command=AdminUserRoleChangeBO.builder().operatorId(10L).targetUserId(target)
                    .targetRole(role).reason("synthetic obsolete role request").build();
            var error=assertThrows(ContractProblemException.class,()->service.changeUserRole(command));
            assertEquals(409,error.getStatus().value());
            assertEquals("/problems/authorization-source-migration-required",error.getType());
        }
        verifyNoInteractions(profiles,authorities,guard,authorization,audit,cache);
    }
}
