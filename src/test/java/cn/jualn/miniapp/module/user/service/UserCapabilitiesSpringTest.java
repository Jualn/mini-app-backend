package cn.jualn.miniapp.module.user.service;

import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.module.admin.operation.service.AdminAuditJournal;
import cn.jualn.miniapp.module.authorization.service.CurrentAuthorization;
import cn.jualn.miniapp.module.authorization.service.RbacGovernanceService;
import cn.jualn.miniapp.module.user.entity.UserAgreement;
import cn.jualn.miniapp.module.user.mapper.UserAgreementMapper;
import cn.jualn.miniapp.module.user.mapper.UserAuthRow;
import cn.jualn.miniapp.module.user.mapper.UserProfileMapper;
import java.sql.Connection;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real Spring bean/proxy and JDBC transaction manager; JDBC, cache and persistence are doubles. */
class UserCapabilitiesSpringTest {
    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    @Import({UserAccountService.class, UserAgreementService.class, UserWechatIdentityService.class})
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
        @Bean DataSourceTransactionManager transactions(DataSource source) {
            return new DataSourceTransactionManager(source);
        }
        @Bean UserProfileMapper profiles() { return mock(UserProfileMapper.class); }
        @Bean UserAgreementMapper agreements() { return mock(UserAgreementMapper.class); }
        @Bean RedisService cache() { return mock(RedisService.class); }
        @Bean RbacGovernanceService governance() { return mock(RbacGovernanceService.class); }
        @Bean CurrentAuthorization authorization() { return mock(CurrentAuthorization.class); }
        @Bean AdminAuditJournal audit() { return mock(AdminAuditJournal.class); }
        @Bean cn.jualn.miniapp.module.user.converter.UserConverter converter() {
            return org.mapstruct.factory.Mappers.getMapper(cn.jualn.miniapp.module.user.converter.UserConverter.class);
        }
        @Bean cn.jualn.miniapp.module.authorization.service.AuthorizationAuthorityService authorities() {
            return mock(cn.jualn.miniapp.module.authorization.service.AuthorizationAuthorityService.class);
        }
    }

    @Test void loginCheckUsesIndependentTransactionAndCurrentDatabaseStatus() throws Exception {
        try (var context = new AnnotationConfigApplicationContext(Config.class)) {
            var mapper = context.getBean(UserProfileMapper.class);
            var row = new UserAuthRow(); row.setId(7L); row.setRole(1); row.setStatus(3);
            when(mapper.selectUserAuthRowByUserId(7L)).thenAnswer(invocation -> {
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                return row;
            });
            var transactions = new TransactionTemplate(context.getBean(DataSourceTransactionManager.class));
            transactions.executeWithoutResult(status -> {
                assertThrows(BusinessException.class,
                        () -> context.getBean(UserAccountService.class).assertLoginAllowed(7L));
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                assertFalse(status.isRollbackOnly());
            });
            verify(context.getBean(DataSource.class), times(2)).getConnection();
            verifyNoInteractions(context.getBean(RedisService.class));
        }
    }

    @Test void agreementAndIdentityWritesCrossTheirSpringTransactionBoundaries() {
        try (var context = new AnnotationConfigApplicationContext(Config.class)) {
            UserContext.setUserId(7L);
            var agreements = context.getBean(UserAgreementMapper.class);
            doAnswer(invocation -> {
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                return 1;
            }).when(agreements).insert(any(UserAgreement.class));
            context.getBean(UserAgreementService.class).agreeCurrentAgreement("v1");
            verify(agreements).insert(any(UserAgreement.class));

            var profiles = context.getBean(UserProfileMapper.class);
            when(profiles.bindMpOpenidIfUnchanged(7L, "official-id")).thenAnswer(invocation -> {
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                return 1;
            });
            context.getBean(UserWechatIdentityService.class).bindOfficialAccountIdentity(7L, "official-id");
            verify(profiles).bindMpOpenidIfUnchanged(7L, "official-id");
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        } finally {
            UserContext.clear();
        }
    }
}
