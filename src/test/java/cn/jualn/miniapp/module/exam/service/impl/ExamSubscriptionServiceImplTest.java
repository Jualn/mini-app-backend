package cn.jualn.miniapp.module.exam.service.impl;

import cn.jualn.miniapp.common.constant.RedisKeyConstant;
import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.infrastructure.validator.TargetValidator;
import cn.jualn.miniapp.module.exam.entity.ExamSubscription;
import cn.jualn.miniapp.module.exam.mapper.ExamSubscriptionMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExamSubscriptionServiceImplTest {

    @Mock
    private ExamSubscriptionMapper examSubscriptionMapper;
    @Mock
    private RedisService redisService;
    @Mock
    private TargetValidator targetValidator;

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void isSubscribed_shouldReturnTrueWhenCached() {
        UserContext.setUserId(12L);
        ExamSubscriptionServiceImpl service = new ExamSubscriptionServiceImpl(examSubscriptionMapper, redisService, targetValidator);
        when(redisService.getString(RedisKeyConstant.examSubscription(88L, 12L))).thenReturn("1");

        assertTrue(service.isSubscribed(88L));

        verify(examSubscriptionMapper, never()).selectOne(any());
    }

    @Test
    void subscribeExam_shouldRejectWhenAlreadySubscribed() {
        UserContext.setUserId(12L);
        ExamSubscriptionServiceImpl service = new ExamSubscriptionServiceImpl(examSubscriptionMapper, redisService, targetValidator);
        when(redisService.getString(RedisKeyConstant.examSubscription(88L, 12L))).thenReturn("1");

        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () -> service.subscribeExam(88L));

        verify(examSubscriptionMapper, never()).insert(any(ExamSubscription.class));
    }
}

