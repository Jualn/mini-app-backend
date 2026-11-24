package cn.jualn.miniapp.module.activity.service;

import cn.jualn.miniapp.module.activity.service.*;
import cn.jualn.miniapp.module.eventcontent.service.EventContactCodec;
import cn.jualn.miniapp.module.eventcontent.service.EventContentService;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.module.activity.bo.*;
import cn.jualn.miniapp.module.activity.converter.ActivityConverter;
import cn.jualn.miniapp.module.activity.mapper.ActivityMapper;
import cn.jualn.miniapp.module.interact.service.InteractionLikeQueries;
import cn.jualn.miniapp.module.media.service.MediaService;
import cn.jualn.miniapp.module.notify.service.NotifyService;
import cn.jualn.miniapp.module.timeline.service.TimelineService;
import cn.jualn.miniapp.module.user.service.UserService;
import java.util.*;

import cn.jualn.miniapp.module.activity.service.impl.ActivityServiceImpl;

/** Test wiring only; assertions call the individual production capability. Audit/review collaborators are explicit doubles. */
public class ActivityTestServices {
    private ActivityQueries queries;
    private ActivityAdminQueries adminQueries;
    private ActivityLifecycleService lifecycle;
    private ActivityDraftService drafts;
    private ActivityReviewService reviews;
    private ActivityServiceImpl legacy;

    public ActivityTestServices(cn.jualn.miniapp.module.authorization.service.CurrentAuthorization authorization,
            EventContentService eventContentService,
            ActivityMapper activityMapper,
            ActivityConverter activityConverter,
            ActivityEnrollmentService activityEnrollmentService,
            MediaService mediaService,
            cn.jualn.miniapp.module.media.service.MediaQueries mediaQueries,
            UserService userService,
            TimelineService timelineService,
            RedisService redisService,
            InteractionLikeQueries interactService,
            NotifyService notifyService,
            EventContactCodec eventContactCodec,
            ActivityParticipationPolicy participationPolicy,
            ActivityRegistrationService activityRegistrationService) {
        queries = new ActivityQueries(eventContentService, activityMapper, activityConverter, activityEnrollmentService, mediaQueries, userService, timelineService, redisService, interactService, eventContactCodec, participationPolicy, activityRegistrationService);
        adminQueries = new ActivityAdminQueries(authorization, eventContentService, activityMapper, activityEnrollmentService, mediaQueries, timelineService);
        lifecycle = new ActivityLifecycleService(authorization, eventContentService, activityMapper, org.mockito.Mockito.mock(ActivityPublicHistory.class), timelineService, redisService, notifyService, participationPolicy, adminQueries);
        drafts = new ActivityDraftService(authorization, eventContentService, activityMapper, org.mockito.Mockito.mock(ActivityPublicHistory.class), mediaService, timelineService, redisService, activityRegistrationService, lifecycle, adminQueries, org.mockito.Mockito.mock(cn.jualn.miniapp.module.admin.operation.service.AdminAuditJournal.class));
        reviews = new ActivityReviewService(authorization);
        legacy = new ActivityServiceImpl();
        managementAudit(org.mockito.Mockito.mock(cn.jualn.miniapp.module.admin.operation.service.AdminAuditJournal.class));
        var provider = org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class,
                invocation -> org.mockito.Mockito.mock(cn.jualn.miniapp.module.audit.service.ContentSafetyInvalidation.class));
        org.springframework.test.util.ReflectionTestUtils.setField(lifecycle, "safetyReviews", provider);
    }

    public void managementAudit(cn.jualn.miniapp.module.admin.operation.service.AdminAuditJournal audit) {
        org.springframework.test.util.ReflectionTestUtils.setField(drafts, "managementAudit", audit);
        org.springframework.test.util.ReflectionTestUtils.setField(lifecycle, "managementAudit", audit);
    }

    public ActivityTestServices transactions(org.springframework.transaction.PlatformTransactionManager manager) {
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

    public ActivityQueries queries() { return queries; }

    public ActivityAdminQueries adminQueries() { return adminQueries; }

    public ActivityLifecycleService lifecycle() { return lifecycle; }

    public ActivityDraftService drafts() { return drafts; }

    public ActivityReviewService reviews() { return reviews; }

    public ActivityServiceImpl legacy() { return legacy; }
}
