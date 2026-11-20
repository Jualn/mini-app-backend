package cn.jualn.miniapp.module.learningtask;
import cn.jualn.miniapp.module.notify.service.inbox.NotificationStreamCursorCodec;
import cn.jualn.miniapp.module.notify.service.inbox.NotificationSnapshotProjection;
import cn.jualn.miniapp.module.notify.service.inbox.NotificationCenterServiceImpl;

import cn.jualn.miniapp.module.person.service.facts.PersonCatalog;
import cn.jualn.miniapp.module.person.service.access.PersonBusinessScopes;
import cn.jualn.miniapp.module.person.service.access.PersonAuthorityGate;

import cn.jualn.miniapp.module.authorization.service.CurrentAuthorization;
import cn.jualn.miniapp.module.authorization.service.RbacAuthorizationReader;
import cn.jualn.miniapp.module.authorization.service.RbacCatalog;
import cn.jualn.miniapp.module.authorization.mapper.RbacMapper;
import cn.jualn.miniapp.module.admin.auth.support.AdminPermissionPolicy;
import cn.jualn.miniapp.module.audit.service.LearningTaskTextSafetyCheckService;
import cn.jualn.miniapp.module.learningtask.service.LearningTaskAuthorizationPolicy;
import cn.jualn.miniapp.module.learningtask.service.LearningTaskNotifications;
import cn.jualn.miniapp.module.learningtask.service.UnavailableLearningTaskProviders;
import cn.jualn.miniapp.module.notify.mapper.NotificationMapper;
import cn.jualn.miniapp.module.notify.service.inbox.NotificationInboxService;
import cn.jualn.miniapp.module.notify.service.NotifyService;
import cn.jualn.miniapp.module.user.mapper.UserProfileMapper;
import cn.jualn.miniapp.module.user.service.UserLearningTaskRecipientService;
import cn.jualn.miniapp.module.user.service.UserWechatIdentityService;
import cn.jualn.miniapp.third.wx.client.WxClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.mockito.Mockito.mock;

/** Isolated wiring: actual LT/User/RBAC/Notification/Job persistence, only Wx and cache are external doubles. */
@TestConfiguration
@Import({cn.jualn.miniapp.module.person.service.access.PersonAuthorityGate.class,cn.jualn.miniapp.module.admin.operation.AdminAuditTestConfiguration.class,cn.jualn.miniapp.module.learningtask.service.LearningTaskScopePolicy.class,
        cn.jualn.miniapp.module.person.service.access.PersonBusinessScopes.class,cn.jualn.miniapp.module.person.service.facts.PersonCatalog.class,
        cn.jualn.miniapp.module.user.service.UserCurrentLoginAccess.class,
        cn.jualn.miniapp.module.authorization.service.TransactionalAuthorization.class,
        cn.jualn.miniapp.module.authorization.service.UnifiedDutyReader.class,cn.jualn.miniapp.module.authorization.service.UnifiedDutyCatalog.class,
        cn.jualn.miniapp.module.authorization.service.UnifiedAuthorizationService.class,
        CurrentAuthorization.class,RbacAuthorizationReader.class,AdminPermissionPolicy.class,LearningTaskAuthorizationPolicy.class,
        UnavailableLearningTaskProviders.class,LearningTaskTextSafetyCheckService.class,UserLearningTaskRecipientService.class,
        LearningTaskNotifications.class,NotificationInboxService.class,cn.jualn.miniapp.infrastructure.async.job.JobService.class,
        cn.jualn.miniapp.module.learningtask.service.LearningTaskReminderHandler.class,
        cn.jualn.miniapp.infrastructure.async.job.JobClaimService.class,
        cn.jualn.miniapp.module.notify.service.inbox.NotificationCenterServiceImpl.class,
        cn.jualn.miniapp.module.notify.service.inbox.NotificationSnapshotProjection.class,
        cn.jualn.miniapp.module.notify.service.inbox.NotificationStreamCursorCodec.class,
        cn.jualn.miniapp.module.notify.controller.CanonicalNotificationController.class})
class LearningTaskVerticalTestBeans {
    @Bean io.micrometer.core.instrument.MeterRegistry meters() { return new io.micrometer.core.instrument.simple.SimpleMeterRegistry(); }
    @Bean cn.jualn.miniapp.infrastructure.cache.RedisService redisProjection() { return mock(cn.jualn.miniapp.infrastructure.cache.RedisService.class); }
    @Bean cn.jualn.miniapp.module.notify.service.inbox.PersonalNotificationInbox notificationCompatibility() { return mock(cn.jualn.miniapp.module.notify.service.inbox.PersonalNotificationInbox.class); }
    @Bean cn.jualn.miniapp.module.notify.service.preference.NotificationPreferences notificationPreferences() { return mock(cn.jualn.miniapp.module.notify.service.preference.NotificationPreferences.class); }
    @Bean cn.jualn.miniapp.module.notify.service.capability.NotificationCapabilityQueries notificationCapabilities() { return mock(cn.jualn.miniapp.module.notify.service.capability.NotificationCapabilityQueries.class); }
    @Bean cn.jualn.miniapp.module.notify.converter.NotificationPersonalConverter personalConverter() { return new cn.jualn.miniapp.module.notify.converter.NotificationPersonalConverter(); }

    @Bean cn.jualn.miniapp.module.notify.converter.NotificationCenterConverter centerConverter() { return org.mapstruct.factory.Mappers.getMapper(cn.jualn.miniapp.module.notify.converter.NotificationCenterConverter.class); }
    @Bean RbacCatalog testCatalog() throws Exception {
        var constructor=RbacCatalog.class.getDeclaredConstructor(java.util.Set.class); constructor.setAccessible(true);
        java.util.Set<String> codes=new java.util.HashSet<>();
        try(var input=new org.springframework.core.io.ClassPathResource("rbac/rbac-v1.json").getInputStream()) {
            var artifact=new ObjectMapper().readTree(input);
            for(var permission:artifact.get("permissions")) { codes.add(permission.get("code").asText()); }
        }
        return constructor.newInstance(codes); // TEST ONLY support manifest; production constructor remains closed.
    }
    @Bean MapperFactoryBean<cn.jualn.miniapp.module.person.mapper.PersonMapper> personMapper(SqlSessionFactory s){return mapper(cn.jualn.miniapp.module.person.mapper.PersonMapper.class,s);}
    @Bean MapperFactoryBean<cn.jualn.miniapp.module.person.mapper.PersonBusinessMapper> businessMapper(SqlSessionFactory s){return mapper(cn.jualn.miniapp.module.person.mapper.PersonBusinessMapper.class,s);}
    @Bean MapperFactoryBean<cn.jualn.miniapp.module.authorization.mapper.UnifiedMapper> unifiedMapper(SqlSessionFactory s){return mapper(cn.jualn.miniapp.module.authorization.mapper.UnifiedMapper.class,s);}
    @Bean MapperFactoryBean<RbacMapper> rbacMapper(SqlSessionFactory sessions) { return mapper(RbacMapper.class,sessions); }
    @Bean MapperFactoryBean<UserProfileMapper> userMapper(SqlSessionFactory sessions) { return mapper(UserProfileMapper.class,sessions); }
    @Bean MapperFactoryBean<NotificationMapper> notificationMapper(SqlSessionFactory sessions) { return mapper(NotificationMapper.class,sessions); }
    @Bean MapperFactoryBean<cn.jualn.miniapp.infrastructure.async.job.AsyncJobMapper> jobMapper(SqlSessionFactory sessions) {
        return mapper(cn.jualn.miniapp.infrastructure.async.job.AsyncJobMapper.class,sessions);
    }
    private <T> MapperFactoryBean<T> mapper(Class<T> type,SqlSessionFactory sessions) {
        var factory=new MapperFactoryBean<>(type); factory.setSqlSessionFactory(sessions); return factory;
    }
    @Bean WxClient wx() { return mock(WxClient.class); }
    @Bean UserWechatIdentityService userFacade(UserProfileMapper mapper) {
        UserWechatIdentityService users=mock(UserWechatIdentityService.class);
        org.mockito.Mockito.when(users.getMiniOpenid(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> mapper.selectMiniOpenid(invocation.getArgument(0)));
        return users;
    }
    @Bean cn.jualn.miniapp.module.user.service.UserAccountService accounts(UserProfileMapper mapper) {
        var accounts=mock(cn.jualn.miniapp.module.user.service.UserAccountService.class);
        org.mockito.Mockito.doAnswer(invocation -> {
            if(!Integer.valueOf(1).equals(mapper.selectStatusById(invocation.getArgument(0)))) {
                throw new cn.jualn.miniapp.common.exception.BusinessException(cn.jualn.miniapp.common.result.ResultCode.FORBIDDEN);
            }
            return null;
        }).when(accounts).assertLoginAllowed(org.mockito.ArgumentMatchers.any());
        return accounts;
    }
    @Bean cn.jualn.miniapp.module.user.service.UserService profiles() { return mock(cn.jualn.miniapp.module.user.service.UserService.class); }
    @Bean TransactionTemplate transactionTemplate(PlatformTransactionManager manager) { return new TransactionTemplate(manager); }
    @Bean NotifyService notifications(NotificationMapper mapper,NotificationInboxService inbox,
            cn.jualn.miniapp.infrastructure.async.job.JobService jobs,ObjectMapper json,TransactionTemplate transactions,cn.jualn.miniapp.module.user.service.UserService users,UserProfileMapper profiles) {
        return new cn.jualn.miniapp.module.notify.service.impl.NotifyServiceImpl(mapper,
                    new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
                    mock(cn.jualn.miniapp.module.notify.mapper.NotifyPlanMapper.class),
                    users,
                    java.util.List.of(new cn.jualn.miniapp.module.activity.service.ActivityNotificationSource(mock(cn.jualn.miniapp.module.activity.service.ActivityEnrollmentService.class),mock(cn.jualn.miniapp.module.timeline.service.TimelineService.class)),new cn.jualn.miniapp.module.publicevent.service.PublicEventNotificationSource(mock(cn.jualn.miniapp.module.publicevent.service.PublicEventSubscriptionService.class))),
                    mock(cn.jualn.miniapp.infrastructure.cache.RedisService.class),
                    mock(cn.jualn.miniapp.module.notify.converter.NotifyConverter.class),
                    jobs,
                    transactions,
                    new cn.jualn.miniapp.module.notify.service.impl.NotificationCreationService(mapper,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
                inbox,
                mock(cn.jualn.miniapp.module.notify.service.NotificationDeliveryService.class),
                mock(cn.jualn.miniapp.infrastructure.cache.RedisService.class),
                mock(cn.jualn.miniapp.module.subject.service.SubjectExistenceService.class),
                json,
                mock(cn.jualn.miniapp.module.notify.mapper.NotificationPreferenceMapper.class),
                new cn.jualn.miniapp.module.user.service.UserNotificationIdentityService(profiles),
                java.util.List.of(new cn.jualn.miniapp.module.activity.service.ActivityNotificationSource(mock(cn.jualn.miniapp.module.activity.service.ActivityEnrollmentService.class),mock(cn.jualn.miniapp.module.timeline.service.TimelineService.class)),new cn.jualn.miniapp.module.publicevent.service.PublicEventNotificationSource(mock(cn.jualn.miniapp.module.publicevent.service.PublicEventSubscriptionService.class)))),
                    java.util.List.of(),
                    mock(cn.jualn.miniapp.module.notify.reminder.ReminderReconcileService.class));
    }
}
