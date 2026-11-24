package cn.jualn.miniapp.module.publicevent.service;

import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.module.publicevent.mapper.PublicEventMapper;
import cn.jualn.miniapp.module.publicevent.mapper.PublicEventSubscriptionMapper;
import cn.jualn.miniapp.module.publicevent.converter.PublicEventConverter;
import cn.jualn.miniapp.module.publicevent.service.PublicEventSubscriptionService;
import cn.jualn.miniapp.module.interact.service.InteractionLikeQueries;
import cn.jualn.miniapp.module.media.service.MediaService;
import cn.jualn.miniapp.module.notify.service.NotifyService;
import cn.jualn.miniapp.module.timeline.service.TimelineService;
import cn.jualn.miniapp.module.user.service.UserService;

import cn.jualn.miniapp.module.publicevent.service.impl.PublicEventServiceImpl;

/** Test wiring only; assertions call the individual production capability. Audit/review collaborators are explicit doubles. */
public class PublicEventTestServices {
    private PublicEventQueries queries;
    private PublicEventAdminQueries adminQueries;
    private PublicEventLifecycleService lifecycle;
    private PublicEventDraftService drafts;
    private PublicEventServiceImpl legacy;

    public PublicEventTestServices(cn.jualn.miniapp.module.authorization.service.CurrentAuthorization authorization,
            cn.jualn.miniapp.module.eventcontent.service.EventContentService eventContentService,
            PublicEventMapper examInfoMapper,
            PublicEventSubscriptionMapper examSubscriptionMapper,
            PublicEventConverter examConverter,
            MediaService mediaService,
            cn.jualn.miniapp.module.media.service.MediaQueries mediaQueries,
            TimelineService timelineService,
            UserService userService,
            RedisService redisService,
            InteractionLikeQueries interactService,
            PublicEventSubscriptionService examSubscriptionService,
            NotifyService notifyService,
            cn.jualn.miniapp.module.eventcontent.service.EventContactCodec eventContactCodec) {
        queries = new PublicEventQueries(eventContentService, examInfoMapper, examConverter, mediaQueries, timelineService, interactService, examSubscriptionService, eventContactCodec);
        adminQueries = new PublicEventAdminQueries(authorization, examInfoMapper, queries);
        lifecycle = new PublicEventLifecycleService(authorization, examInfoMapper, timelineService, redisService, notifyService, queries);
        drafts = new PublicEventDraftService(authorization, eventContentService, examInfoMapper, examConverter, mediaService, timelineService, lifecycle, queries);
        legacy = new PublicEventServiceImpl();
        managementAudit(org.mockito.Mockito.mock(cn.jualn.miniapp.module.admin.operation.service.AdminAuditJournal.class));
        var provider = org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class,
                invocation -> org.mockito.Mockito.mock(cn.jualn.miniapp.module.audit.service.ContentSafetyInvalidation.class));
        org.springframework.test.util.ReflectionTestUtils.setField(lifecycle, "safetyReviews", provider);
    }

    public void managementAudit(cn.jualn.miniapp.module.admin.operation.service.AdminAuditJournal audit) {
        org.springframework.test.util.ReflectionTestUtils.setField(drafts, "managementAudit", audit);
        org.springframework.test.util.ReflectionTestUtils.setField(lifecycle, "managementAudit", audit);
    }

    public PublicEventTestServices transactions(org.springframework.transaction.PlatformTransactionManager manager) {
        drafts = proxy(drafts, manager);
        lifecycle = proxy(lifecycle, manager);
        return this;
    }
    @SuppressWarnings("unchecked")
    private <T> T proxy(T target, org.springframework.transaction.PlatformTransactionManager manager) {
        var proxy = new org.springframework.aop.framework.ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(manager, new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        return (T) proxy.getProxy();
    }

    public PublicEventQueries queries() { return queries; }

    public PublicEventAdminQueries adminQueries() { return adminQueries; }

    public PublicEventLifecycleService lifecycle() { return lifecycle; }

    public PublicEventDraftService drafts() { return drafts; }

    public PublicEventServiceImpl legacy() { return legacy; }
}
