package cn.jualn.miniapp.common.web;

import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.infrastructure.validator.TargetValidator;
import cn.jualn.miniapp.module.activity.entity.Activity;
import cn.jualn.miniapp.module.activity.entity.ActivityEnrollment;
import cn.jualn.miniapp.module.activity.mapper.ActivityEnrollmentMapper;
import cn.jualn.miniapp.module.activity.mapper.ActivityMapper;
import cn.jualn.miniapp.module.activity.service.ActivityEnrollmentService;
import cn.jualn.miniapp.module.activity.service.impl.ActivityEnrollmentServiceImpl;
import cn.jualn.miniapp.module.exam.entity.ExamInfo;
import cn.jualn.miniapp.module.exam.entity.ExamSubscription;
import cn.jualn.miniapp.module.exam.mapper.ExamInfoMapper;
import cn.jualn.miniapp.module.exam.mapper.ExamSubscriptionMapper;
import cn.jualn.miniapp.module.exam.service.ExamSubscriptionService;
import cn.jualn.miniapp.module.exam.service.impl.ExamSubscriptionServiceImpl;
import java.sql.Connection;
import java.time.LocalDateTime;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Real Spring proxy and JDBC transaction manager; JDBC/Mapper doubles do not prove database invariants. */
class SubscriptionTransactionBoundaryTest {
    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class Transactions {
    }

    @AfterEach
    void clearIdentity() {
        UserContext.clear();
    }

    @Test
    void activityWriteAndReadbackShareTheManagedTransaction() throws Exception {
        var connection = mock(Connection.class);
        var activities = mock(ActivityMapper.class);
        var relations = mock(ActivityEnrollmentMapper.class);
        when(activities.selectForUpdate(10L)).thenReturn(Activity.builder().id(10L).publishStatus(1).lifecycleStatus(0).build());
        when(relations.selectOne(any())).thenReturn(null, ActivityEnrollment.builder().id(1L).activityId(10L)
                .userId(7L).status(1).createdAt(LocalDateTime.of(2026, 9, 13, 12, 0)).build());
        when(relations.insert(any(ActivityEnrollment.class))).thenAnswer(call -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            return 1;
        });
        UserContext.setUserId(7L);
        try (var context = context(connection)) {
            context.registerBean(ActivityEnrollmentServiceImpl.class, () -> new ActivityEnrollmentServiceImpl(
                    relations, activities, mock(RedisService.class), mock(TargetValidator.class)));
            context.refresh();
            assertTrue(context.getBean(ActivityEnrollmentService.class).subscribeWithState(10L).subscribed());
            verify(connection).commit();
            verify(connection, never()).rollback();
        }
    }

    @Test
    void publicEventReadbackFailureRollsBackTheManagedWrite() throws Exception {
        var connection = mock(Connection.class);
        var events = mock(ExamInfoMapper.class);
        var relations = mock(ExamSubscriptionMapper.class);
        when(events.selectForUpdate(10L)).thenReturn(ExamInfo.builder().id(10L).publishStatus(1).lifecycleStatus(0).build());
        when(relations.selectOne(any())).thenReturn(null).thenThrow(new IllegalStateException("readback failed"));
        when(relations.insert(any(ExamSubscription.class))).thenAnswer(call -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            return 1;
        });
        UserContext.setUserId(7L);
        try (var context = context(connection)) {
            context.registerBean(ExamSubscriptionServiceImpl.class, () -> new ExamSubscriptionServiceImpl(
                    events, relations, mock(RedisService.class), mock(TargetValidator.class)));
            context.refresh();
            assertThrows(IllegalStateException.class,
                    () -> context.getBean(ExamSubscriptionService.class).subscribeWithState(10L));
            verify(connection).rollback();
            verify(connection, never()).commit();
        }
    }

    private AnnotationConfigApplicationContext context(Connection connection) throws Exception {
        var dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        var context = new AnnotationConfigApplicationContext();
        context.register(Transactions.class);
        context.registerBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(dataSource));
        return context;
    }
}
