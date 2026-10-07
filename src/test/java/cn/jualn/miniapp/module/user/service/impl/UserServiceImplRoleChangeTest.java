package cn.jualn.miniapp.module.user.service.impl;

import cn.jualn.miniapp.common.enums.UserRole;
import cn.jualn.miniapp.common.enums.UserStatus;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.module.admin.operation.service.AdminOperationLogService;
import cn.jualn.miniapp.module.media.service.MediaService;
import cn.jualn.miniapp.module.user.bo.AdminUserRoleChangeBO;
import cn.jualn.miniapp.module.user.converter.UserConverter;
import cn.jualn.miniapp.module.user.entity.UserProfile;
import cn.jualn.miniapp.module.user.mapper.UserAgreementMapper;
import cn.jualn.miniapp.module.user.mapper.UserProfileMapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceImplRoleChangeTest {

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, UserProfile.class);
    }

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

    private UserServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new UserServiceImpl(
                userProfileMapper,
                userAgreementMapper,
                redisService,
                userConverter,
                eventPublisher,
                mediaService,
                adminOperationLogService, org.mockito.Mockito.mock(cn.jualn.miniapp.module.audit.service.ProfileSafetyCheckService.class), org.mockito.Mockito.mock(org.springframework.transaction.PlatformTransactionManager.class));
    }

    @Test
    void changeUserRole_shouldUpdateAndRecordAudit() {
        UserProfile target = UserProfile.builder()
                .id(20L)
                .role(UserRole.USER.getCode())
                .status(UserStatus.NORMAL.getCode())
                .build();
        when(userProfileMapper.selectById(20L)).thenReturn(target);
        when(userProfileMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        service.changeUserRole(command(10L, 20L, UserRole.OPR));

        verify(userProfileMapper).update(isNull(), any(Wrapper.class));
        verify(adminOperationLogService).recordUserRoleChange(
                10L, 20L, UserRole.USER, UserRole.OPR, "补充校园运营职责");
    }

    @Test
    void changeUserRole_shouldRejectChangingSelf() {
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.changeUserRole(command(10L, 10L, UserRole.OPR)));

        assertEquals("不能修改当前管理员自己的角色", exception.getMessage());
        verify(userProfileMapper, never()).selectById(any());
    }

    @Test
    void changeUserRole_shouldKeepLastAdministrator() {
        UserProfile target = UserProfile.builder()
                .id(20L)
                .role(UserRole.ADMIN.getCode())
                .status(UserStatus.NORMAL.getCode())
                .build();
        when(userProfileMapper.selectById(20L)).thenReturn(target);
        when(userProfileMapper.selectAdminIdsForUpdate()).thenReturn(List.of(20L));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.changeUserRole(command(10L, 20L, UserRole.USER)));

        assertEquals("不能移除最后一个管理员", exception.getMessage());
        verify(userProfileMapper, never()).update(isNull(), any(Wrapper.class));
        verify(adminOperationLogService, never()).recordUserRoleChange(
                any(), any(), any(), any(), any());
    }

    private AdminUserRoleChangeBO command(Long operatorId, Long targetId, UserRole targetRole) {
        return AdminUserRoleChangeBO.builder()
                .operatorId(operatorId)
                .targetUserId(targetId)
                .targetRole(targetRole)
                .reason("补充校园运营职责")
                .build();
    }
}
