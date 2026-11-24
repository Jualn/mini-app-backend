package cn.jualn.miniapp.module.publicevent.service.impl;

import cn.jualn.miniapp.common.constant.RedisKeyConstant;
import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.module.subject.service.SubjectExistenceService;
import cn.jualn.miniapp.module.publicevent.entity.PublicEventSubscription;
import cn.jualn.miniapp.module.publicevent.mapper.PublicEventSubscriptionMapper;
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
class PublicEventSubscriptionServiceImplTest {
    @Mock private cn.jualn.miniapp.module.publicevent.mapper.PublicEventMapper examInfoMapper;

    @Mock
    private PublicEventSubscriptionMapper examSubscriptionMapper;
    @Mock
    private RedisService redisService;
    @Mock
    private SubjectExistenceService targetValidator;

    @org.junit.jupiter.api.BeforeEach
    void setup() {
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        var assistant = new org.apache.ibatis.builder.MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(), "");
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, PublicEventSubscription.class);
    }
    @AfterEach
    void tearDown() {
        UserContext.clear();
        org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
    }

    @Test
    void isSubscribed_shouldReturnTrueWhenCached() {
        UserContext.setUserId(12L);
        PublicEventSubscriptionServiceImpl service = new PublicEventSubscriptionServiceImpl(examInfoMapper, examSubscriptionMapper, redisService);
        when(redisService.getString(RedisKeyConstant.examSubscription(88L, 12L))).thenReturn("1");

        assertTrue(service.isSubscribed(88L));

        verify(examSubscriptionMapper, never()).selectOne(any());
    }

    @Test
    void subscribeExam_shouldBeIdempotentWhenAlreadySubscribed() {
        UserContext.setUserId(12L);
        PublicEventSubscriptionServiceImpl service = new PublicEventSubscriptionServiceImpl(examInfoMapper, examSubscriptionMapper, redisService);
        when(examInfoMapper.selectForUpdate(88L)).thenReturn(cn.jualn.miniapp.module.publicevent.entity.PublicEvent.builder().id(88L).publishStatus(1).lifecycleStatus(0).build());
        when(examSubscriptionMapper.selectOne(any())).thenReturn(PublicEventSubscription.builder().id(1L).status(1).build());

        service.subscribeExam(88L);

        verify(examSubscriptionMapper, never()).insert(any(PublicEventSubscription.class));
    }

    @Test
    void cancelledRelationshipRemainsReadableWithoutLoadingUnavailableSubject() {
        UserContext.setUserId(7L);
        var service = new PublicEventSubscriptionServiceImpl(examInfoMapper, examSubscriptionMapper, redisService);
        when(examSubscriptionMapper.selectOne(any())).thenReturn(PublicEventSubscription.builder().id(1L).status(2).build());
        var state = service.getSubscriptionState(10L);
        org.junit.jupiter.api.Assertions.assertFalse(state.subscribed());
        org.junit.jupiter.api.Assertions.assertNull(state.subscribedAt());
        org.mockito.Mockito.verifyNoInteractions(examInfoMapper, redisService);
    }

    @Test
    void absentRelationshipDoesNotExposeAnUnavailableSubject() {
        UserContext.setUserId(7L);
        var service = new PublicEventSubscriptionServiceImpl(examInfoMapper, examSubscriptionMapper, redisService);
        org.junit.jupiter.api.Assertions.assertThrows(cn.jualn.miniapp.common.exception.BusinessException.class,
                () -> service.getSubscriptionState(10L));
        verify(examInfoMapper).selectByIdNotDeleted(10L);
        org.mockito.Mockito.verifyNoInteractions(redisService);
    }
}

