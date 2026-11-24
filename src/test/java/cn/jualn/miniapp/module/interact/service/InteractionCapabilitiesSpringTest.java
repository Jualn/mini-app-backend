package cn.jualn.miniapp.module.interact.service;

import cn.jualn.miniapp.common.constant.RedisKeyConstant;
import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.module.article.service.ArticleService;
import cn.jualn.miniapp.module.comment.service.ArticleCommentNotifications;
import cn.jualn.miniapp.module.comment.service.CommentNotificationFactsService;
import cn.jualn.miniapp.module.comment.service.CommentTargetAccess;
import cn.jualn.miniapp.module.interact.entity.*;
import cn.jualn.miniapp.module.interact.mapper.*;
import cn.jualn.miniapp.module.interact.service.impl.*;
import cn.jualn.miniapp.module.notify.payload.NotifyPayload;
import cn.jualn.miniapp.module.notify.service.NotifyService;
import cn.jualn.miniapp.module.post.service.PostNotificationFactsService;
import cn.jualn.miniapp.module.user.bo.UserSimpleBO;
import cn.jualn.miniapp.module.user.service.UserAccountService;
import cn.jualn.miniapp.module.user.service.UserService;
import java.sql.Connection;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real Spring wiring, proxies and JDBC transaction manager; JDBC, persistence and effects are doubles. */
class InteractionCapabilitiesSpringTest {
    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    @Import({InteractionLikes.class, InteractionLikeQueries.class, InteractionCounts.class,
            InteractionShares.class, InteractionViews.class, InteractionAccess.class,
            LikeCache.class, LikeNotifications.class})
    static class Config {
        @Bean cn.jualn.miniapp.module.post.service.PostPublicAccess legacyPostAccess(){
            var access=mock(cn.jualn.miniapp.module.post.service.PostPublicAccess.class);
            when(access.require(anyLong(),anyBoolean())).thenReturn(new cn.jualn.miniapp.module.post.bo.PostCommentAccessBO(100L,"LEGACY"));
            return access;
        }

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
        @Bean cn.jualn.miniapp.module.interact.mapper.PostInteractionMapper postFacts(){return mock(cn.jualn.miniapp.module.interact.mapper.PostInteractionMapper.class);}
        @Bean cn.jualn.miniapp.module.post.service.PostCommunityService community(){return mock(cn.jualn.miniapp.module.post.service.PostCommunityService.class);}
        @Bean LikeRecordMapper likes() { return mock(LikeRecordMapper.class); }
        @Bean ShareRecordMapper shares() { return mock(ShareRecordMapper.class); }
        @Bean ViewLogMapper views() { return mock(ViewLogMapper.class); }
        @Bean RedisService cache() { return mock(RedisService.class); }
        @Bean InteractionSubjects subjects() { return mock(InteractionSubjects.class); }
        @Bean PostNotificationFactsService posts() { return mock(PostNotificationFactsService.class); }
        @Bean CommentNotificationFactsService comments() { return mock(CommentNotificationFactsService.class); }
        @Bean UserService users() { return mock(UserService.class); }
        @Bean UserAccountService accounts() { return mock(UserAccountService.class); }
        @Bean NotifyService notifications() { return mock(NotifyService.class); }
        @Bean ArticleService articles() { return mock(ArticleService.class); }
        @Bean CommentTargetAccess access() { return mock(CommentTargetAccess.class); }
        @Bean ArticleCommentNotifications articleComments() { return mock(ArticleCommentNotifications.class); }
    }

    @BeforeAll static void mapperMetadata() {
        var assistant = new org.apache.ibatis.builder.MapperBuilderAssistant(
                new com.baomidou.mybatisplus.core.MybatisConfiguration(), "");
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, LikeRecord.class);
    }
    @BeforeEach void identity() { UserContext.setUserId(7L); }
    @AfterEach void clearIdentity() { UserContext.clear(); }

    private void notificationFacts(AnnotationConfigApplicationContext context) {
        when(context.getBean(PostNotificationFactsService.class).published(42L))
                .thenReturn(new PostNotificationFactsService.Fact(42, 8, "post title", "post quote"));
        when(context.getBean(UserService.class).getSimpleInfo(7L))
                .thenReturn(UserSimpleBO.builder().id(7L).nickname("actor").build());
    }

    @Test void newLikeFreezesNotificationInTheWriteTransactionAndUpdatesCacheAfterCommit() {
        try (var context = new AnnotationConfigApplicationContext(Config.class)) {
            notificationFacts(context);
            var source = context.getBean(DataSource.class);
            var held = new AtomicReference<Object>();
            var mapper = context.getBean(LikeRecordMapper.class);
            var cache = context.getBean(RedisService.class);
            var notifications = context.getBean(NotifyService.class);
            when(mapper.insert(any(LikeRecord.class))).thenAnswer(invocation -> {
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                held.set(TransactionSynchronizationManager.getResource(source));
                return 1;
            });
            when(cache.getLong(RedisKeyConstant.likeCount("post", 42L))).thenReturn(2L);
            doAnswer(invocation -> {
                assertSame(held.get(), TransactionSynchronizationManager.getResource(source));
                verifyNoInteractions(cache);
                return null;
            }).when(notifications).processNotificationPayload(any());

            context.getBean(InteractionLikes.class).like(TargetType.POST, 42L);

            var payload = ArgumentCaptor.forClass(NotifyPayload.class);
            verify(notifications).processNotificationPayload(payload.capture());
            assertEquals("like:POST:42:actor:7:recipient:8", payload.getValue().getSourceKey());
            assertEquals("post quote", payload.getValue().getSnapshot().presentation().quote());
            verify(cache).set(RedisKeyConstant.likeStatus(7L, "post", 42L), "1", RedisKeyConstant.LIKE_STATUS_TTL);
            verify(cache).incrementAndRefresh(RedisKeyConstant.likeCount("post", 42L), RedisKeyConstant.LIKE_COUNT_TTL);
            verify(cache).sAdd(RedisKeyConstant.LIKE_DIRTY_SET, RedisKeyConstant.likeCount("post", 42L));
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        }
    }

    @Test void notificationFailureRollsBackTheTransactionAndDoesNotRunLikeCacheEffects() throws Exception {
        try (var context = new AnnotationConfigApplicationContext(Config.class)) {
            notificationFacts(context);
            var source = context.getBean(DataSource.class);
            var connection = new AtomicReference<Connection>();
            when(context.getBean(LikeRecordMapper.class).insert(any(LikeRecord.class))).thenAnswer(invocation -> {
                connection.set(((ConnectionHolder) TransactionSynchronizationManager.getResource(source)).getConnection());
                return 1;
            });
            doThrow(new IllegalStateException("synthetic notification failure"))
                    .when(context.getBean(NotifyService.class)).processNotificationPayload(any());

            assertThrows(IllegalStateException.class,
                    () -> context.getBean(InteractionLikes.class).like(TargetType.POST, 42L));

            verify(connection.get()).rollback();
            verify(connection.get(), never()).commit();
            verifyNoInteractions(context.getBean(RedisService.class));
        }
    }

    @Test void duplicateLikeRefreshesStatusWithoutAnotherNotificationOrCounterChange() {
        try (var context = new AnnotationConfigApplicationContext(Config.class)) {
            when(context.getBean(LikeRecordMapper.class).insert(any(LikeRecord.class)))
                    .thenThrow(new DuplicateKeyException("synthetic duplicate"));
            context.getBean(InteractionLikes.class).like(TargetType.POST, 42L);
            var cache = context.getBean(RedisService.class);
            verify(cache).set(RedisKeyConstant.likeStatus(7L, "post", 42L), "1", RedisKeyConstant.LIKE_STATUS_TTL);
            verifyNoMoreInteractions(cache);
            verifyNoInteractions(context.getBean(NotifyService.class), context.getBean(PostNotificationFactsService.class));
        }
    }

    @Test void missingLikeDeletionRefreshesNegativeStatusWithoutDecrementingCount() {
        try (var context = new AnnotationConfigApplicationContext(Config.class)) {
            context.getBean(InteractionLikes.class).unlike(TargetType.POST, 42L);
            var cache = context.getBean(RedisService.class);
            verify(cache).setNullPlaceholder(RedisKeyConstant.likeStatus(7L, "post", 42L), RedisKeyConstant.LIKE_STATUS_TTL);
            verifyNoMoreInteractions(cache);
            verifyNoInteractions(context.getBean(NotifyService.class));
        }
    }

    @Test void shareOwnsItsRecordAndArticleStatisticsWithoutLikeOrNotificationEffects() {
        try (var context = new AnnotationConfigApplicationContext(Config.class)) {
            var mapper = context.getBean(ShareRecordMapper.class);
            when(mapper.insert(any(ShareRecord.class))).thenAnswer(invocation -> {
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                return 1;
            });
            context.getBean(InteractionShares.class).share(TargetType.ARTICLE, 42L, 1);
            var record = ArgumentCaptor.forClass(ShareRecord.class);
            verify(mapper).insert(record.capture());
            assertEquals(7L, record.getValue().getUserId());
            assertEquals(1, record.getValue().getPlatform());
            verify(context.getBean(ArticleService.class)).rebuildStatistics(42L);
            verifyNoInteractions(context.getBean(LikeRecordMapper.class), context.getBean(NotifyService.class),
                    context.getBean(RedisService.class));
        }
    }

    @Test void articleViewsKeepDailyDeduplicationAndOrdinaryViewsKeepRedisDeltas() {
        try (var context = new AnnotationConfigApplicationContext(Config.class)) {
            var mapper = context.getBean(ViewLogMapper.class);
            when(mapper.insert(any(ViewLog.class))).thenReturn(1).thenThrow(new DuplicateKeyException("same day"));
            var views = context.getBean(InteractionViews.class);
            views.view(TargetType.ARTICLE, 42L);
            views.view(TargetType.ARTICLE, 42L);
            var records = ArgumentCaptor.forClass(ViewLog.class);
            verify(mapper, times(2)).insert(records.capture());
            assertTrue(records.getAllValues().stream().allMatch(row -> row.getUserId().equals(7L)
                    && row.getViewDay().equals(LocalDate.now(ZoneId.of("Asia/Shanghai")))));
            verify(context.getBean(ArticleService.class)).rebuildStatistics(42L);
            var cache = context.getBean(RedisService.class);
            verifyNoInteractions(cache);
            when(cache.incrementAndRefresh(RedisKeyConstant.viewDelta("post", 43L), RedisKeyConstant.VIEW_DELTA_TTL))
                    .thenAnswer(invocation -> {
                        assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                        return 1L;
                    });
            views.view(TargetType.POST, 43L);
            verify(cache).sAdd(RedisKeyConstant.VIEW_DIRTY_SET, RedisKeyConstant.viewDelta("post", 43L));
            verifyNoInteractions(context.getBean(NotifyService.class), context.getBean(LikeRecordMapper.class));
        }
    }

    @Test void hiddenSubjectsCannotUseCountCachesOrPersistenceFallbacks() {
        try (var context = new AnnotationConfigApplicationContext(Config.class)) {
            doThrow(new BusinessException(ResultCode.POST_NOT_FOUND)).when(context.getBean(CommentTargetAccess.class))
                    .requireSubject(TargetType.POST, 42L, false);
            var counts = context.getBean(InteractionCounts.class);
            assertThrows(BusinessException.class, () -> counts.getLikeCount(TargetType.POST, 42L));
            assertThrows(BusinessException.class, () -> counts.getShareCount(TargetType.POST, 42L));
            assertThrows(BusinessException.class, () -> counts.getViewCount(TargetType.POST, 42L));
            verifyNoInteractions(context.getBean(RedisService.class), context.getBean(LikeRecordMapper.class),
                    context.getBean(ShareRecordMapper.class));
        }
    }
}
