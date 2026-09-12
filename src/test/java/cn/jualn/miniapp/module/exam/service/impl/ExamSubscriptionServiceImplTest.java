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
    @Mock private cn.jualn.miniapp.module.exam.mapper.ExamInfoMapper examInfoMapper;

    @Mock
    private ExamSubscriptionMapper examSubscriptionMapper;
    @Mock
    private RedisService redisService;
    @Mock
    private TargetValidator targetValidator;

    @org.junit.jupiter.api.BeforeEach
    void setup() {
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        var assistant = new org.apache.ibatis.builder.MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(), "");
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, ExamSubscription.class);
    }
    @AfterEach
    void tearDown() {
        UserContext.clear();
        org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
    }

    @Test
    void isSubscribed_shouldReturnTrueWhenCached() {
        UserContext.setUserId(12L);
        ExamSubscriptionServiceImpl service = new ExamSubscriptionServiceImpl(examInfoMapper, examSubscriptionMapper, redisService, targetValidator);
        when(redisService.getString(RedisKeyConstant.examSubscription(88L, 12L))).thenReturn("1");

        assertTrue(service.isSubscribed(88L));

        verify(examSubscriptionMapper, never()).selectOne(any());
    }

    @Test
    void subscribeExam_shouldBeIdempotentWhenAlreadySubscribed() {
        UserContext.setUserId(12L);
        ExamSubscriptionServiceImpl service = new ExamSubscriptionServiceImpl(examInfoMapper, examSubscriptionMapper, redisService, targetValidator);
        when(examInfoMapper.selectForUpdate(88L)).thenReturn(cn.jualn.miniapp.module.exam.entity.ExamInfo.builder().id(88L).publishStatus(1).build());
        when(examSubscriptionMapper.selectOne(any())).thenReturn(ExamSubscription.builder().id(1L).status(1).build());

        service.subscribeExam(88L);

        verify(examSubscriptionMapper, never()).insert(any(ExamSubscription.class));
    }
}

