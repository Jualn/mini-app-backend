package cn.jualn.miniapp.module.user.service.impl;

import cn.jualn.miniapp.common.enums.UserRole;
import cn.jualn.miniapp.common.enums.UserStatus;
import cn.jualn.miniapp.common.pagination.AdminIdCursorCodec;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.module.admin.operation.service.AdminOperationLogService;
import cn.jualn.miniapp.module.media.service.MediaService;
import cn.jualn.miniapp.module.user.bo.AdminUserPageBO;
import cn.jualn.miniapp.module.user.bo.AdminUserQueryBO;
import cn.jualn.miniapp.module.user.converter.UserConverter;
import cn.jualn.miniapp.module.user.mapper.AdminUserListRow;
import cn.jualn.miniapp.module.user.mapper.AdminUserSummaryRow;
import cn.jualn.miniapp.module.user.mapper.UserAgreementMapper;
import cn.jualn.miniapp.module.user.mapper.UserProfileMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceImplAdminCursorTest {

    @Mock
    private UserProfileMapper userProfileMapper;
    @Mock
    private UserAgreementMapper userAgreementMapper;
    @Mock
    private RedisService redisService;
    @Mock
    private UserConverter userConverter;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private MediaService mediaService;
    @Mock
    private AdminOperationLogService adminOperationLogService;

    @Test
    void pageAdminUsers_shouldDecodeAndReturnOpaqueCursor() {
        UserServiceImpl service = new UserServiceImpl(
                userProfileMapper,
                userAgreementMapper,
                redisService,
                userConverter,
                eventPublisher,
                mediaService,
                adminOperationLogService);
        String cursor = AdminIdCursorCodec.encode("latest-login", 200L);
        AdminUserListRow row = new AdminUserListRow();
        row.setId(101L);
        row.setRole(UserRole.USER.getCode());
        row.setStatus(UserStatus.NORMAL.getCode());
        AdminUserSummaryRow summary = new AdminUserSummaryRow();
        summary.setTotal(0L);
        summary.setActive(0L);
        summary.setMuted(0L);
        summary.setBanned(0L);
        summary.setOperators(0L);
        summary.setDeactivated(0L);

        when(userProfileMapper.selectAdminUserPage(
                null, null, null, null, null, "latest-login", 200L, 21))
                .thenReturn(List.of(row));
        when(userProfileMapper.selectAdminUserSummary()).thenReturn(summary);

        AdminUserPageBO result = service.pageAdminUsers(AdminUserQueryBO.builder()
                .sort("latest-login")
                .cursor(cursor)
                .pageSize(20)
                .build());

        assertEquals(101L, AdminIdCursorCodec.decode(result.getNextCursor(), "latest-login"));
    }
}
