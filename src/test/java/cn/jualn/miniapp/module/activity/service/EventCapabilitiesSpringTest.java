package cn.jualn.miniapp.module.activity.service;

import cn.dev33.satoken.context.mock.SaTokenContextMockUtil;
import cn.jualn.miniapp.common.constant.RedisKeyConstant;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.common.security.AdminStpUtil;
import cn.jualn.miniapp.common.web.StrongEtag;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.module.activity.bo.AdminActivitySaveBO;
import cn.jualn.miniapp.module.activity.converter.ActivityConverter;
import cn.jualn.miniapp.module.activity.entity.Activity;
import cn.jualn.miniapp.module.activity.mapper.ActivityMapper;
import cn.jualn.miniapp.module.admin.operation.service.AdminAuditJournal;
import cn.jualn.miniapp.module.audit.service.ContentSafetyInvalidation;
import cn.jualn.miniapp.module.authorization.service.CurrentAuthorization;
import cn.jualn.miniapp.module.comment.mapper.CommentMapper;
import cn.jualn.miniapp.module.comment.service.CommentCounters;
import cn.jualn.miniapp.module.eventcontent.service.EventContactCodec;
import cn.jualn.miniapp.module.eventcontent.service.EventContentService;
import cn.jualn.miniapp.module.interact.service.InteractionLikeQueries;
import cn.jualn.miniapp.module.media.TestMediaService;
import cn.jualn.miniapp.module.notify.service.NotifyService;
import cn.jualn.miniapp.module.publicevent.converter.PublicEventConverter;
import cn.jualn.miniapp.module.publicevent.entity.PublicEvent;
import cn.jualn.miniapp.module.publicevent.mapper.PublicEventMapper;
import cn.jualn.miniapp.module.publicevent.service.*;
import cn.jualn.miniapp.module.timeline.service.TimelineService;
import cn.jualn.miniapp.module.user.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real Spring wiring, proxies and JDBC transaction manager; persistence, JDBC and effects are doubles. */
class EventCapabilitiesSpringTest {
    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    @Import({ActivityQueries.class, ActivityAdminQueries.class, ActivityDraftService.class,
            ActivityLifecycleService.class, ActivityReviewService.class, PublicEventQueries.class,
            PublicEventAdminQueries.class, PublicEventDraftService.class, PublicEventLifecycleService.class,
            ActivityInteractionSubject.class, PublicEventInteractionSubject.class, CommentCounters.class})
    static class Config {
        @Bean DataSource dataSource() throws Exception {
            var source = mock(DataSource.class);
            when(source.getConnection()).thenAnswer(invocation -> {
                var connection = mock(Connection.class);
                when(connection.getAutoCommit()).thenReturn(true);
                return connection;
            });
            return source;
        }
        @Bean DataSourceTransactionManager transactions(DataSource source) { return new DataSourceTransactionManager(source); }
        @Bean CurrentAuthorization authorization() { return mock(CurrentAuthorization.class); }
        @Bean EventContentService content() { return mock(EventContentService.class); }
        @Bean ActivityMapper activities() { return mock(ActivityMapper.class); }
        @Bean PublicEventMapper events() { return mock(PublicEventMapper.class); }
        @Bean ActivityConverter activityConverter() { return Mappers.getMapper(ActivityConverter.class); }
        @Bean PublicEventConverter eventConverter() { return Mappers.getMapper(PublicEventConverter.class); }
        @Bean ActivityEnrollmentService enrollment() { return mock(ActivityEnrollmentService.class); }
        @Bean ActivityRegistrationService registration() { return mock(ActivityRegistrationService.class); }
        @Bean PublicEventSubscriptionService subscriptions() { return mock(PublicEventSubscriptionService.class); }
        @Bean TestMediaService media() { return mock(TestMediaService.class); }
        @Bean UserService users() { return mock(UserService.class); }
        @Bean TimelineService timelines() { return mock(TimelineService.class); }
        @Bean RedisService cache() { return mock(RedisService.class); }
        @Bean InteractionLikeQueries interactions() { return mock(InteractionLikeQueries.class); }
        @Bean NotifyService notifications() { return mock(NotifyService.class); }
        @Bean EventContactCodec contacts() { return new EventContactCodec(new ObjectMapper()); }
        @Bean ActivityParticipationPolicy participation() { return new ActivityParticipationPolicy(); }
        @Bean AdminAuditJournal audit() { return mock(AdminAuditJournal.class); }
        @Bean ContentSafetyInvalidation reviews() { return mock(ContentSafetyInvalidation.class); }
        @Bean CommentMapper comments() { return mock(CommentMapper.class); }
        @Bean cn.jualn.miniapp.module.post.service.PostInteractionSubject posts() {
            return mock(cn.jualn.miniapp.module.post.service.PostInteractionSubject.class);
        }
        @Bean cn.jualn.miniapp.module.article.service.ArticleService articles() {
            return mock(cn.jualn.miniapp.module.article.service.ArticleService.class);
        }
    }

    @org.junit.jupiter.api.BeforeAll static void mapperMetadata() {
        var assistant = new org.apache.ibatis.builder.MapperBuilderAssistant(
                new com.baomidou.mybatisplus.core.MybatisConfiguration(), "");
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, Activity.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, PublicEvent.class);
    }

    @BeforeEach void identity() {
        SaTokenContextMockUtil.setMockContext();
        AdminStpUtil.STP_LOGIC.switchTo(9L);
    }
    @AfterEach void clearIdentity() { SaTokenContextMockUtil.clearContext(); }

    @Test void draftEditUsesLifecycleRulesAndReminderInTheSameTransaction() {
        try (var context = new AnnotationConfigApplicationContext(Config.class)) {
            var source = context.getBean(DataSource.class);
            var held = new AtomicReference<Object>();
            var mapper = context.getBean(ActivityMapper.class);
            var row = Activity.builder().id(7L).contractVersion(1L).publishStatus(0).lifecycleStatus(0)
                    .participantMode(1).registrationMode(1).build();
            when(mapper.selectForUpdate(7L)).thenAnswer(invocation -> {
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                var resource = TransactionSynchronizationManager.getResource(source);
                if (held.get() == null) held.set(resource); else assertSame(held.get(), resource);
                return row;
            });
            when(mapper.update(isNull(), any())).thenReturn(1);
            when(mapper.selectAdminActivityById(7L)).thenReturn(row);
            var notify = context.getBean(NotifyService.class);
            doAnswer(invocation -> {
                assertSame(held.get(), TransactionSynchronizationManager.getResource(source));
                verifyNoInteractions(context.getBean(RedisService.class));
                return null;
            }).when(notify).reconcileReminders(eq(TargetType.ACTIVITY), eq(7L), anyLong(), anyList(), any());

            var command = AdminActivitySaveBO.builder().id(7L).operatorId(9L).title("draft")
                    .registrationMode(1).participantMode(1).build();
            context.getBean(ActivityDraftService.class).replaceAdminActivity(command, StrongEtag.of("activity", 7L, 1L));

            verify(notify).reconcileReminders(eq(TargetType.ACTIVITY), eq(7L), eq(1L), anyList(), any());
            verify(context.getBean(RedisService.class)).delete(RedisKeyConstant.activityDetail(7L));
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        }
    }

    @Test void staleDraftVersionCannotWriteChildrenOrPlanEffects() {
        try (var context = new AnnotationConfigApplicationContext(Config.class)) {
            var mapper = context.getBean(ActivityMapper.class);
            when(mapper.selectForUpdate(7L)).thenReturn(Activity.builder().id(7L).contractVersion(2L).build());
            var command = AdminActivitySaveBO.builder().id(7L).operatorId(9L).build();
            var problem = assertThrows(ContractProblemException.class, () -> context.getBean(ActivityDraftService.class)
                    .replaceAdminActivity(command, StrongEtag.of("activity", 7L, 1L)));
            assertEquals(412, problem.getStatus().value());
            verify(mapper, never()).update(any(), any());
            verifyNoInteractions(context.getBean(EventContentService.class), context.getBean(NotifyService.class),
                    context.getBean(TestMediaService.class), context.getBean(RedisService.class));
        }
    }

    @Test void publicEventPublicationKeepsStateAndEffectsInOneTransactionWithoutPersonalReads() {
        try (var context = new AnnotationConfigApplicationContext(Config.class)) {
            var source = context.getBean(DataSource.class);
            var held = new AtomicReference<Object>();
            var row = PublicEvent.builder().id(8L).contractVersion(1L).publishStatus(0).lifecycleStatus(0)
                    .title("event").summary("summary").eventType(1).sourceName("source")
                    .sourceUrl("https://example.com/source").build();
            var mapper = context.getBean(PublicEventMapper.class);
            when(mapper.selectForUpdate(8L)).thenAnswer(invocation -> {
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                var resource = TransactionSynchronizationManager.getResource(source);
                if (held.get() == null) held.set(resource); else assertSame(held.get(), resource);
                return row;
            });
            when(mapper.transitionContract(8L, 1L, 1, 0, true)).thenAnswer(invocation -> {
                row.setPublishStatus(1); row.setContractVersion(2L); return 1;
            });
            var notify = context.getBean(NotifyService.class);
            doAnswer(invocation -> {
                assertSame(held.get(), TransactionSynchronizationManager.getResource(source));
                return null;
            }).when(notify).reconcileReminders(eq(TargetType.EXAM), eq(8L), anyLong(), anyList(), any());

            var result = context.getBean(PublicEventLifecycleService.class).transitionAdminPublicEvent(
                    8L, 9L, StrongEtag.of("public-event", 8L, 1L), "publish");

            assertEquals(2L, result.getContractVersion());
            verify(notify).reconcileReminders(eq(TargetType.EXAM), eq(8L), eq(2L), anyList(), any());
            verify(context.getBean(RedisService.class)).delete(RedisKeyConstant.examDetail(8L));
            verifyNoInteractions(context.getBean(InteractionLikeQueries.class), context.getBean(PublicEventSubscriptionService.class));
        }
    }

    @Test void commentEffectsUseExistingSubjectOwnersAndRequireTheCallersTransaction() {
        try (var context = new AnnotationConfigApplicationContext(Config.class)) {
            var counters = context.getBean(CommentCounters.class);
            var activity = context.getBean(ActivityMapper.class);
            var event = context.getBean(PublicEventMapper.class);
            assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,
                    () -> counters.increase(2, 7L, 11L));
            verifyNoInteractions(activity, event, context.getBean(CommentMapper.class));
            doAnswer(invocation -> {
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                return null;
            }).when(activity).increaseCommentCount(7L);
            new TransactionTemplate(context.getBean(DataSourceTransactionManager.class)).executeWithoutResult(status -> {
                counters.increase(2, 7L, 11L);
                counters.decrease(2, 7L, 11L);
                counters.increase(3, 8L, null);
                counters.decrease(3, 8L, null);
            });
            verify(activity).increaseCommentCount(7L);
            verify(activity).decreaseCommentCount(7L);
            verify(event).increaseCommentCount(8L);
            verify(event).decreaseCommentCount(8L);
            verify(context.getBean(CommentMapper.class)).increaseReplyCount(11L);
            verify(context.getBean(CommentMapper.class)).decreaseReplyCount(11L);
            verify(context.getBean(RedisService.class), times(2)).delete(RedisKeyConstant.activityDetail(7L));
        }
    }
}
