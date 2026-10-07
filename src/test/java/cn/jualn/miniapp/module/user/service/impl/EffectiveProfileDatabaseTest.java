package cn.jualn.miniapp.module.user.service.impl;

import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.common.enums.AuditScene;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.module.admin.operation.service.AdminOperationLogService;
import cn.jualn.miniapp.module.audit.service.ProfileSafetyCheckService;
import cn.jualn.miniapp.module.media.bo.ProfileMediaSnapshotBO;
import cn.jualn.miniapp.module.media.service.MediaService;
import cn.jualn.miniapp.module.user.bo.UserProfileUpdateBO;
import cn.jualn.miniapp.module.user.converter.UserConverter;
import cn.jualn.miniapp.module.user.mapper.UserAgreementMapper;
import cn.jualn.miniapp.module.user.mapper.UserProfileMapper;
import cn.jualn.miniapp.module.user.service.UserService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mapstruct.factory.Mappers;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.util.List;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real MySQL, Mapper/XML and Spring transaction proxy; providers/cache are isolated doubles. */
@EnabledIfSystemProperty(named = "event.test.jdbcUrl", matches = "jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/user_profile_contract_.*")
class EffectiveProfileDatabaseTest {
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private UserService service;
    private ProfileSafetyCheckService safety;
    private MediaService media;
    private final long id = 910_000_001L;

    @BeforeEach void setup() {
        context = new AnnotationConfigApplicationContext(Config.class);
        jdbc = new JdbcTemplate(context.getBean(DataSource.class));
        service = context.getBean(UserService.class);
        Object target = org.springframework.test.util.AopTestUtils.getTargetObject(service);
        ReflectionTestUtils.setField(target, "profileWritesEnabled", true);
        safety = context.getBean(ProfileSafetyCheckService.class);
        media = context.getBean(MediaService.class);
        jdbc.update("DELETE FROM user_profile WHERE id BETWEEN ? AND ?", id, id + 2);
        for (int role = 1; role <= 3; role++) {
            jdbc.update("INSERT INTO user_profile(id,openid,nickname,bio,role,status) VALUES (?,?, '原昵称','原简介',?,1)",
                    id + role - 1, "synthetic-profile-" + role, role);
        }
        UserContext.setUserId(id);
    }
    @AfterEach void close() {
        UserContext.clear();
        jdbc.update("DELETE FROM user_profile WHERE id BETWEEN ? AND ?", id, id + 2);
        context.close();
    }
    private String field(String column) { return jdbc.queryForObject("SELECT " + column + " FROM user_profile WHERE id=?", String.class, id); }
    private void unchanged() { assertEquals("原昵称", field("nickname")); assertEquals("原简介", field("bio")); assertEquals("0", field("profile_revision")); }

    @Test void readsShareFiveFactsAndReliableOperatorMapping() {
        assertEquals(service.getEffectiveProfile(null), service.getEffectiveProfile(id));
        assertFalse(service.getEffectiveProfile(id).isPlatformOperator());
        assertTrue(service.getEffectiveProfile(id + 1).isPlatformOperator());
        assertTrue(service.getEffectiveProfile(id + 2).isPlatformOperator());
        assertNull(service.getEffectiveProfile(id).getAvatarUrl());
        assertThrows(BusinessException.class, () -> service.getEffectiveProfile(id + 100));
        var summaries = context.getBean(UserProfileMapper.class).selectSimpleBatch(List.of(id, id + 1, id + 2));
        assertEquals(2, summaries.stream().filter(cn.jualn.miniapp.module.user.bo.UserSimpleBO::isPlatformOperator).count());
    }
    @Test void actorAuthenticationAndExistingRestrictionAreEnforced() {
        UserContext.clear();
        assertThrows(BusinessException.class, () -> service.getEffectiveProfile(id));
        UserContext.setUserId(id);
        jdbc.update("UPDATE user_profile SET status=3 WHERE id=?", id);
        assertThrows(BusinessException.class, () -> service.getEffectiveProfile(id + 1));
        assertThrows(BusinessException.class, () -> service.updateEffectiveProfile(UserProfileUpdateBO.builder().bio("x").build()));
        unchanged();
    }
    @Test void nicknameDuplicatesAndUnicodeCodePointsAreAllowed() {
        String nickname = "😀".repeat(10);
        var command = UserProfileUpdateBO.builder().nickname(nickname).build();
        assertEquals(nickname, service.updateEffectiveProfile(command).getNickname());
        UserContext.setUserId(id + 1);
        assertEquals(nickname, service.updateEffectiveProfile(UserProfileUpdateBO.builder().nickname(nickname).build()).getNickname());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM user_profile WHERE nickname=?", Integer.class, nickname));
    }
    @Test void checkedNicknameCommitsWithOmittedFieldsPreserved() {
        doAnswer(call -> { assertFalse(TransactionSynchronizationManager.isActualTransactionActive()); unchanged(); return null; })
                .when(safety).checkText(anyString(), eq("新昵称"));
        var result = service.updateEffectiveProfile(UserProfileUpdateBO.builder().nickname("新昵称").build());
        assertEquals("新昵称", field("nickname")); assertEquals("原简介", result.getBio()); assertEquals("1", field("profile_revision"));
    }
    @Test void rejectionAcrossMultipleFieldsHasZeroDatabaseChanges() {
        doThrow(ProfileSafetyCheckService.rejected()).when(safety).checkText(anyString(), eq("违规简介"));
        var failure = assertThrows(ContractProblemException.class, () -> service.updateEffectiveProfile(
                UserProfileUpdateBO.builder().nickname("新昵称").bio("违规简介").build()));
        assertEquals(422, failure.getStatus().value()); unchanged();
    }
    @Test void unavailableChecksHaveZeroDatabaseChanges() {
        doThrow(ProfileSafetyCheckService.unavailable()).when(safety).checkText(anyString(), eq("新简介"));
        assertEquals(503, assertThrows(ContractProblemException.class, () -> service.updateEffectiveProfile(
                UserProfileUpdateBO.builder().bio("新简介").build())).getStatus().value()); unchanged();
    }
    @Test void emptyBioIsClearedAndNullHistoricalBioReadsAsEmpty() {
        assertEquals("", service.updateEffectiveProfile(UserProfileUpdateBO.builder().bio("").build()).getBio());
        jdbc.update("UPDATE user_profile SET bio=NULL WHERE id=?", id);
        assertEquals("", service.getEffectiveProfile(null).getBio());
    }
    @Test void emptyBlankAndOverlongCommandsCannotCommit() {
        assertThrows(BusinessException.class, () -> service.updateEffectiveProfile(new UserProfileUpdateBO()));
        for (String value : List.of("", " \t\n", "😀".repeat(11))) {
            assertThrows(ContractProblemException.class, () -> service.updateEffectiveProfile(UserProfileUpdateBO.builder().nickname(value).build()));
        }
        unchanged(); verifyNoInteractions(safety);
    }
    @Test void legacyPutDelegatesToTheSameCheckBeforeCommit() {
        doThrow(ProfileSafetyCheckService.rejected()).when(safety).checkText(anyString(), eq("违规昵称"));
        assertThrows(ContractProblemException.class, () -> service.updateCurrentProfile(UserProfileUpdateBO.builder().nickname("违规昵称").gender(1).build()));
        unchanged(); assertEquals("0", field("gender"));
    }
    @Test void oldSlowRequestCannotOverwriteNewCommittedRequest() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        doAnswer(call -> { entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); return null; })
                .when(safety).checkText(anyString(), eq("旧请求"));
        var executor = Executors.newSingleThreadExecutor();
        try {
            var old = executor.submit(() -> {
                UserContext.setUserId(id);
                try { return service.updateEffectiveProfile(UserProfileUpdateBO.builder().nickname("旧请求").build()); }
                finally { UserContext.clear(); }
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            unchanged();
            assertEquals("原昵称", service.getEffectiveProfile(null).getNickname());
            service.updateEffectiveProfile(UserProfileUpdateBO.builder().nickname("新请求").build());
            release.countDown();
            var failure = assertThrows(ExecutionException.class, () -> old.get(5, TimeUnit.SECONDS));
            assertInstanceOf(ContractProblemException.class, failure.getCause());
            assertEquals("新请求", field("nickname")); assertEquals("1", field("profile_revision"));
        } finally { release.countDown(); executor.shutdownNow(); }
    }
    private void snapshot() {
        when(media.resolveOwnedUploadUrl(TargetType.USER, "user/test/avatar")).thenReturn("https://media.example/source");
        when(media.prepareProfileSnapshot("user/test/avatar")).thenReturn(new ProfileMediaSnapshotBO("profile-effective/test/copy", "https://media.example/frozen"));
    }
    @Test void imagePassCommitsTheCheckedSnapshotAndBindsItAtomically() {
        snapshot();
        doAnswer(call -> { assertNull(field("avatar_url")); assertFalse(TransactionSynchronizationManager.isActualTransactionActive()); return null; })
                .when(safety).checkMedia(id, AuditScene.USER_AVATAR, "https://media.example/frozen");
        assertEquals("https://media.example/frozen", service.updateEffectiveProfile(UserProfileUpdateBO.builder().avatarObjectKey("user/test/avatar").build()).getAvatarUrl());
        assertEquals("profile-effective/test/copy", field("avatar_snapshot_key"));
        verify(media).bindPendingUploads(TargetType.USER, id, List.of("user/test/avatar", "profile-effective/test/copy"));
    }
    @Test void imageRejectOrUnavailableKeepsTextAndImageUnchanged() {
        snapshot();
        for (ContractProblemException failure : List.of(ProfileSafetyCheckService.rejected(), ProfileSafetyCheckService.unavailable())) {
            doThrow(failure).when(safety).checkMedia(anyLong(), any(), anyString());
            assertThrows(ContractProblemException.class, () -> service.updateEffectiveProfile(UserProfileUpdateBO.builder()
                    .avatarObjectKey("user/test/avatar").nickname("新昵称").bio("新简介").build()));
            unchanged(); assertNull(field("avatar_url"));
        }
        verify(media, never()).bindPendingUploads(any(), any(), any());
    }
    @Test void retainedCurrentReferenceNeedsNoUploadOrRepeatedMediaCheck() {
        jdbc.update("UPDATE user_profile SET avatar_object_key='user/test/avatar',avatar_url='https://media.example/current' WHERE id=?", id);
        when(media.resolveOwnedUploadUrl(TargetType.USER, "user/test/avatar")).thenReturn("https://media.example/source");
        assertEquals("https://media.example/current", service.updateEffectiveProfile(UserProfileUpdateBO.builder().avatarObjectKey("user/test/avatar").build()).getAvatarUrl());
        verify(media, never()).prepareProfileSnapshot(anyString()); verify(safety, never()).checkMedia(any(), any(), any());
    }
    @Test void failedMediaBindingRollsBackWithoutPartialProfile() {
        snapshot();
        doAnswer(call -> {
            jdbc.update("UPDATE user_profile SET gender=2 WHERE id=?", id);
            throw new BusinessException(cn.jualn.miniapp.common.result.ResultCode.INVALID_OPERATION);
        }).when(media).bindPendingUploads(any(), any(), any());
        assertThrows(ContractProblemException.class, () -> service.updateEffectiveProfile(UserProfileUpdateBO.builder()
                .avatarObjectKey("user/test/avatar").nickname("新昵称").build()));
        unchanged(); assertEquals("0", field("gender"));
    }
    @Test void invalidReferenceUsesTheContractPointerAndCannotChangeText() {
        doThrow(new BusinessException(cn.jualn.miniapp.common.result.ResultCode.INVALID_OPERATION))
                .when(media).resolveOwnedUploadUrl(any(), anyString());
        var failure = assertThrows(ContractProblemException.class, () -> service.updateEffectiveProfile(
                UserProfileUpdateBO.builder().avatarObjectKey("user/other/image").nickname("新昵称").build()));
        assertEquals(400, failure.getStatus().value()); assertEquals("/avatarObjectKey", failure.getErrors().get(0).pointer());
        assertEquals("INVALID_REFERENCE", failure.getErrors().get(0).code()); unchanged();
    }
    @Test void lateLegacyCallbacksCannotChangeANewerCommittedProfile() {
        service.updateEffectiveProfile(UserProfileUpdateBO.builder().nickname("新昵称").bio("新简介").build());
        var mapper = context.getBean(UserProfileMapper.class); var cache = context.getBean(RedisService.class);
        var notify = mock(cn.jualn.miniapp.module.notify.service.NotifyService.class);
        var callbacks = List.of(new cn.jualn.miniapp.module.user.audit.UserNicknameAuditCallback(mapper, cache, notify),
                new cn.jualn.miniapp.module.user.audit.UserBioAuditCallback(mapper, cache, notify),
                new cn.jualn.miniapp.module.user.audit.UserAvatarAuditCallback(mapper, cache, notify));
        callbacks.forEach(callback -> { callback.onReject(id, 1L, "old reject"); callback.onPass(id, 1L); });
        assertEquals("新昵称", field("nickname")); assertEquals("新简介", field("bio")); assertEquals("1", field("profile_revision"));
    }
    @Test void canonicalCannotUseLegacyOnlyFields() {
        assertThrows(ContractProblemException.class, () -> service.updateEffectiveProfile(UserProfileUpdateBO.builder().nickname("新昵称").gender(1).build()));
        unchanged();
    }
    @Test void committedWriteSurvivesCacheFailureAndCanonicalReadsBypassStaleCache() {
        var cache = context.getBean(RedisService.class);
        doThrow(new IllegalStateException("synthetic cache outage")).when(cache).delete(anyString());
        assertEquals("新昵称", service.updateEffectiveProfile(UserProfileUpdateBO.builder().nickname("新昵称").build()).getNickname());
        assertEquals("新昵称", service.getEffectiveProfile(null).getNickname());
        assertEquals("1", field("profile_revision"));
    }
    @Test void cacheEvictionRunsAfterTheEffectiveDatabaseCommit() {
        var cache = context.getBean(RedisService.class);
        doAnswer(call -> { assertEquals("新简介", field("bio")); assertEquals("1", field("profile_revision")); return null; })
                .when(cache).delete(anyString());
        doAnswer(call -> { verifyNoInteractions(cache); unchanged(); return null; }).when(safety).checkText(anyString(), eq("新简介"));
        service.updateEffectiveProfile(UserProfileUpdateBO.builder().bio("新简介").build());
        verify(cache, times(3)).delete(anyString());
    }
    @Test void legacyAndCanonicalWritesStayClosedUntilRolloutIsEnabled() {
        Object target = org.springframework.test.util.AopTestUtils.getTargetObject(service);
        ReflectionTestUtils.setField(target, "profileWritesEnabled", false);
        assertThrows(ContractProblemException.class, () -> service.updateEffectiveProfile(UserProfileUpdateBO.builder().bio("x").build()));
        assertThrows(ContractProblemException.class, () -> service.updateCurrentProfile(UserProfileUpdateBO.builder().bio("x").build()));
        unchanged(); verifyNoInteractions(safety);
    }

    @Configuration(proxyBeanMethods = false) @EnableTransactionManagement(proxyTargetClass = true)
    static class Config {
        @Bean DataSource dataSource() { return new DriverManagerDataSource(System.getProperty("event.test.jdbcUrl")
                + "?sslMode=DISABLED&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai", "root", ""); }
        @Bean DataSourceTransactionManager transactions(DataSource data) { return new DataSourceTransactionManager(data); }
        @Bean SqlSessionFactory sqlSessions(DataSource data) throws Exception {
            var factory = new MybatisSqlSessionFactoryBean(); factory.setDataSource(data);
            var configuration = new MybatisConfiguration(); configuration.setMapUnderscoreToCamelCase(true); factory.setConfiguration(configuration);
            var global = new GlobalConfig();
            global.setDbConfig(new GlobalConfig.DbConfig().setLogicDeleteValue("now()").setLogicNotDeleteValue("null"));
            factory.setGlobalConfig(global); factory.setMapperLocations(new ClassPathResource("mapper/UserProfileMapper.xml"));
            return factory.getObject();
        }
        @Bean MapperFactoryBean<UserProfileMapper> profiles(SqlSessionFactory sessions) {
            var factory = new MapperFactoryBean<>(UserProfileMapper.class); factory.setSqlSessionFactory(sessions); return factory;
        }
        @Bean ProfileSafetyCheckService safety() { return mock(ProfileSafetyCheckService.class); }
        @Bean MediaService media() { return mock(MediaService.class); }
        @Bean RedisService cache() { return mock(RedisService.class); }
        @Bean UserService users(UserProfileMapper mapper, DataSourceTransactionManager transactions,
                               ProfileSafetyCheckService safety, MediaService media, RedisService cache) {
            var service = new UserServiceImpl(mapper, mock(UserAgreementMapper.class), cache,
                    Mappers.getMapper(UserConverter.class), mock(ApplicationEventPublisher.class), media,
                    mock(AdminOperationLogService.class), safety, transactions);
            ReflectionTestUtils.setField(service, "profileWritesEnabled", true); return service;
        }
    }
}
