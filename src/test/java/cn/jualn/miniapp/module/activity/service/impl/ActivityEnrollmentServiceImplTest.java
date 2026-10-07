package cn.jualn.miniapp.module.activity.service.impl;

import cn.jualn.miniapp.common.constant.RedisKeyConstant;
import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.infrastructure.validator.TargetValidator;
import cn.jualn.miniapp.module.activity.bo.ActivityEnrollBO;
import cn.jualn.miniapp.module.activity.entity.ActivityEnrollment;
import cn.jualn.miniapp.module.activity.mapper.ActivityEnrollmentMapper;
import cn.jualn.miniapp.module.activity.mapper.ActivityMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ActivityEnrollmentServiceImplTest {

    @Mock
    private ActivityEnrollmentMapper activityEnrollmentMapper;
    @Mock
    private ActivityMapper activityMapper;
    @Mock
    private RedisService redisService;
    @Mock
    private TargetValidator targetValidator;

    @org.junit.jupiter.api.BeforeEach
    void setup() {
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        var assistant = new org.apache.ibatis.builder.MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(), "");
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, ActivityEnrollment.class);
    }
    @AfterEach
    void tearDown() {
        UserContext.clear();
        org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
    }

    @Test
    void enrollActivity_shouldInsertWhenNotEnrolled() {
        UserContext.setUserId(7L);
        ActivityEnrollmentServiceImpl service = new ActivityEnrollmentServiceImpl(activityEnrollmentMapper, activityMapper, redisService, targetValidator);
        when(activityMapper.selectForUpdate(10L)).thenReturn(cn.jualn.miniapp.module.activity.entity.Activity.builder().id(10L).publishStatus(1).lifecycleStatus(0).build());
        when(activityEnrollmentMapper.insert(any(ActivityEnrollment.class))).thenReturn(1);

        service.enrollActivity(10L);

        verify(activityMapper).selectForUpdate(10L);
        verify(activityEnrollmentMapper).insert(any(ActivityEnrollment.class));
    }

    @Test
    void enrollActivity_shouldUseDatabaseAndBeIdempotent() {
        UserContext.setUserId(7L);
        ActivityEnrollmentServiceImpl service = new ActivityEnrollmentServiceImpl(activityEnrollmentMapper, activityMapper, redisService, targetValidator);
        String key = RedisKeyConstant.activityEnrollment(10L, 7L);
        when(activityMapper.selectForUpdate(10L)).thenReturn(cn.jualn.miniapp.module.activity.entity.Activity.builder().id(10L).publishStatus(1).lifecycleStatus(0).build());
        when(activityEnrollmentMapper.selectOne(any())).thenReturn(ActivityEnrollment.builder().id(1L).status(1).build());

        service.enrollActivity(10L);

        verify(activityMapper).selectForUpdate(10L);
        verify(activityEnrollmentMapper, never()).insert(any(ActivityEnrollment.class));
    }

    @Test
    void existingSubscriptionSurvivesSubjectWithdrawalAndKeepsOriginalTimestamp() {
        UserContext.setUserId(7L);
        var service = new ActivityEnrollmentServiceImpl(activityEnrollmentMapper, activityMapper, redisService, targetValidator);
        var subscribedAt = java.time.LocalDateTime.of(2026, 9, 1, 9, 0);
        var relation = ActivityEnrollment.builder().id(1L).activityId(10L).userId(7L)
                .status(1).createdAt(subscribedAt).build();
        when(activityMapper.selectForUpdate(10L)).thenReturn(
                cn.jualn.miniapp.module.activity.entity.Activity.builder()
                        .id(10L).publishStatus(2).lifecycleStatus(0).build());
        when(activityEnrollmentMapper.selectOne(any())).thenReturn(relation);

        var state = service.subscribeWithState(10L);

        assertTrue(state.subscribed());
        assertEquals(subscribedAt, state.subscribedAt());
        verify(activityEnrollmentMapper, never()).insert(any(ActivityEnrollment.class));
    }

    @Test
    void isEnrolled_shouldReturnFalseWhenNullPlaceholderHit() {
        UserContext.setUserId(5L);
        ActivityEnrollmentServiceImpl service = new ActivityEnrollmentServiceImpl(activityEnrollmentMapper, activityMapper, redisService, targetValidator);
        String key = RedisKeyConstant.activityEnrollment(12L, 5L);
        when(redisService.getString(key)).thenReturn(RedisService.NULL_VALUE);
        when(redisService.isNullPlaceholder(RedisService.NULL_VALUE)).thenReturn(true);

        boolean enrolled = service.isEnrolled(12L);

        assertFalse(enrolled);
        verify(activityEnrollmentMapper, never()).selectOne(any());
    }

    @Test
    void isEnrolled_shouldReturnTrueWhenCacheHit() {
        UserContext.setUserId(5L);
        ActivityEnrollmentServiceImpl service = new ActivityEnrollmentServiceImpl(activityEnrollmentMapper, activityMapper, redisService, targetValidator);
        String key = RedisKeyConstant.activityEnrollment(12L, 5L);
        when(redisService.getString(key)).thenReturn("1");

        boolean enrolled = service.isEnrolled(12L);

        assertTrue(enrolled);
        verify(activityEnrollmentMapper, never()).selectOne(any());
    }

    @Test
    void updateNotifyEnable_shouldThrowWhenEnrollmentMissing() {
        UserContext.setUserId(3L);
        ActivityEnrollmentServiceImpl service = new ActivityEnrollmentServiceImpl(activityEnrollmentMapper, activityMapper, redisService, targetValidator);

        ActivityEnrollBO command = ActivityEnrollBO.builder()
                .activityId(8L)
                .notifyEnable(1)
                .build();

        assertThrows(BusinessException.class, () -> service.updateNotifyEnable(command));

        verify(activityEnrollmentMapper, never()).updateById(any(ActivityEnrollment.class));
    }

    @Test
    void cancelledRelationshipRemainsReadableWithoutLoadingUnavailableSubject() {
        UserContext.setUserId(7L);
        var service = new ActivityEnrollmentServiceImpl(activityEnrollmentMapper, activityMapper, redisService, targetValidator);
        when(activityEnrollmentMapper.selectOne(any())).thenReturn(ActivityEnrollment.builder().id(1L).status(2).build());
        var state = service.getSubscriptionState(10L);
        org.junit.jupiter.api.Assertions.assertFalse(state.subscribed());
        org.junit.jupiter.api.Assertions.assertNull(state.subscribedAt());
        org.mockito.Mockito.verifyNoInteractions(activityMapper, redisService);
    }

    @Test
    void absentRelationshipDoesNotExposeAnUnavailableSubject() {
        UserContext.setUserId(7L);
        var service = new ActivityEnrollmentServiceImpl(activityEnrollmentMapper, activityMapper, redisService, targetValidator);
        org.junit.jupiter.api.Assertions.assertThrows(cn.jualn.miniapp.common.exception.BusinessException.class,
                () -> service.getSubscriptionState(10L));
        verify(activityMapper).selectByIdNotDeleted(10L);
        org.mockito.Mockito.verifyNoInteractions(redisService);
    }
}

