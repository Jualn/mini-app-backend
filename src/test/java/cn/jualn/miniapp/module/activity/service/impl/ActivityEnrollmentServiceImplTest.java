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

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void enrollActivity_shouldInsertWhenNotEnrolled() {
        UserContext.setUserId(7L);
        ActivityEnrollmentServiceImpl service = spy(new ActivityEnrollmentServiceImpl(activityEnrollmentMapper, activityMapper, redisService, targetValidator));
        doReturn(false).when(service).isEnrolled(10L);

        service.enrollActivity(10L);

        verify(targetValidator).assertExists(TargetType.ACTIVITY, 10L);
        verify(activityEnrollmentMapper).insert(any(ActivityEnrollment.class));
    }

    @Test
    void enrollActivity_shouldThrowWhenAlreadyEnrolledInCache() {
        UserContext.setUserId(7L);
        ActivityEnrollmentServiceImpl service = new ActivityEnrollmentServiceImpl(activityEnrollmentMapper, activityMapper, redisService, targetValidator);
        String key = RedisKeyConstant.activityEnrollment(10L, 7L);
        when(redisService.getString(key)).thenReturn("1");

        assertThrows(BusinessException.class, () -> service.enrollActivity(10L));

        verify(targetValidator).assertExists(TargetType.ACTIVITY, 10L);
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
}

