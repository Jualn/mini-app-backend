package cn.jualn.miniapp.module.activity.service.impl;

import cn.jualn.miniapp.module.activity.service.ActivityTestServices;
import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.web.StrongEtag;
import cn.jualn.miniapp.module.activity.bo.AdminActivitySaveBO;
import cn.jualn.miniapp.module.activity.converter.AdminActivityConverter;
import cn.jualn.miniapp.module.activity.converter.ActivityConverter;
import cn.jualn.miniapp.module.activity.dto.admin.AdminActivitySaveRequest;
import cn.jualn.miniapp.module.activity.entity.Activity;
import cn.jualn.miniapp.module.activity.mapper.ActivityMapper;
import cn.jualn.miniapp.module.activity.service.*;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.module.eventcontent.service.EventContentService;
import cn.jualn.miniapp.module.eventcontent.service.EventContactCodec;
import cn.jualn.miniapp.module.interact.service.InteractionLikeQueries;
import cn.jualn.miniapp.module.media.TestMediaService;
import cn.jualn.miniapp.module.notify.service.NotifyService;
import cn.jualn.miniapp.module.timeline.service.TimelineService;
import cn.jualn.miniapp.module.user.service.UserService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mybatis.spring.SqlSessionTemplate;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Only a disposable schema fully migrated through V32; dependencies with side effects are substitutes. */
@EnabledIfSystemProperty(named = "event.test.jdbcUrl", matches = "jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/activity_participant_test")
class ActivityParticipantModeDatabaseTest {
    JdbcTemplate jdbc;
    ActivityMapper mapper;
    ActivityTestServices service;
    final AdminActivityConverter converter = new AdminActivityConverter();
    @BeforeEach void setup() throws Exception {
        var ds = new DriverManagerDataSource(System.getProperty("event.test.jdbcUrl")
                + "?sslMode=DISABLED&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai", "root", "");
        jdbc = new JdbcTemplate(ds);
        assertTrue(jdbc.queryForObject("SELECT VERSION()", String.class).startsWith("8.4."));
        assertEquals("32", jdbc.queryForObject("SELECT version FROM flyway_schema_history WHERE success=1 ORDER BY installed_rank DESC LIMIT 1", String.class));
        var configuration = new MybatisConfiguration(); configuration.setMapUnderscoreToCamelCase(true);
        var factory = new MybatisSqlSessionFactoryBean(); factory.setDataSource(ds); factory.setConfiguration(configuration);
        factory.setGlobalConfig(new com.baomidou.mybatisplus.core.config.GlobalConfig()
                .setDbConfig(new com.baomidou.mybatisplus.core.config.GlobalConfig.DbConfig()
                        .setLogicNotDeleteValue("NULL").setLogicDeleteValue("NOW()"))
                .setMetaObjectHandler(new cn.jualn.miniapp.config.MybatisPlusConfig().metaObjectHandler()));
        factory.setMapperLocations(new ClassPathResource("mapper/ActivityMapper.xml"));
        mapper = new SqlSessionTemplate(factory.getObject()).getMapper(ActivityMapper.class);
        var timeline = mock(TimelineService.class);
        when(timeline.listTimelinesByTarget(eq(TargetType.ACTIVITY), anyLong())).thenReturn(List.of());
        var contents = mock(EventContentService.class);
        when(contents.actions(eq(TargetType.ACTIVITY), anyLong())).thenReturn(List.of());
        var implementation = new ActivityTestServices(org.mockito.Mockito.mock(cn.jualn.miniapp.module.authorization.service.CurrentAuthorization.class), contents, mapper, mock(ActivityConverter.class),
                mock(ActivityEnrollmentService.class), mock(TestMediaService.class), mock(TestMediaService.class), mock(UserService.class), timeline,
                mock(RedisService.class), mock(InteractionLikeQueries.class), mock(NotifyService.class), new EventContactCodec(new ObjectMapper()),
                new ActivityParticipationPolicy(), mock(ActivityRegistrationService.class));
        service = implementation.transactions(new DataSourceTransactionManager(ds));
    }
    AdminActivitySaveBO draft(Long id, String mode) {
        var request = new AdminActivitySaveRequest();
        request.setTitle("participant regression"); request.setSummary("summary");
        request.setOrganizer("organizer"); request.setCategory("SPORTS");
        request.setAudienceSummary("students");
        var audience = new AdminActivitySaveRequest.AudienceScope(); audience.setType("CAMPUS");
        request.setAudienceScope(audience); request.setRegistrationMode("NONE"); request.setParticipantMode(mode);
        request.setTimeline(List.of()); request.setSections(List.of()); request.setActions(List.of());
        request.setContacts(List.of()); request.setAttachments(List.of());
        return converter.toSaveBO(request, id, 9L);
    }
    void replace(AdminActivitySaveBO command) {
        var before = mapper.selectById(command.getId());
        service.drafts().replaceAdminActivity(command, StrongEtag.of("activity", command.getId(), before.getContractVersion()));
    }
    void assertMode(long id, Integer stored, String api) {
        assertEquals(stored, mapper.selectById(id).getParticipantMode());
        assertEquals(stored, jdbc.queryForObject("SELECT participant_mode FROM activity WHERE id=?", Integer.class, id));
        var view = converter.toDetailVO(service.adminQueries().getAdminActivityDetail(id));
        var json = new ObjectMapper().valueToTree(view.draft());
        if (api == null) assertFalse(json.has("participantMode")); else assertEquals(api, json.path("participantMode").asText());
    }
    @Test void createReplaceReadExplicitModesAndPartialOmissionUseRealSql() {
        long id = service.drafts().createAdminActivity(draft(null, null));
        assertMode(id, null, null);
        replace(draft(id, "INDIVIDUAL")); assertMode(id, 1, "INDIVIDUAL");
        var legacyUpdate = draft(id, null);
        legacyUpdate.setCanonicalFullReplacement(false);
        legacyUpdate.setTitle("partial edit");
        service.drafts().updateAdminActivity(legacyUpdate);
        assertMode(id, 1, "INDIVIDUAL");
        var team = draft(id, "TEAM"); team.setCapacity(20); team.setCapacityUnit(2);
        replace(team); assertMode(id, 2, "TEAM");
        assertEquals(20, mapper.selectById(id).getCapacity());
        var unknown = draft(id, null); unknown.setCapacity(20); unknown.setCapacityUnit(2);
        replace(unknown); assertMode(id, null, null);
        assertEquals(2, mapper.selectById(id).getCapacityUnit());
        assertEquals("summary", mapper.selectById(id).getSummary());
        assertEquals("[]", mapper.selectById(id).getAudienceDepartmentIds());
        assertEquals(1, mapper.selectById(id).getRegistrationMode());
    }
    @Test void missingModeBlocksPublishAndPublishedReplacementBeforeWriting() {
        long id = service.drafts().createAdminActivity(draft(null, null));
        var before = mapper.selectById(id);
        var error = assertThrows(ContractProblemException.class, () -> service.lifecycle().transitionAdminActivity(
                id, 9L, StrongEtag.of("activity", id, before.getContractVersion()), "publish"));
        assertEquals("/problems/publish-validation-failed", error.getType());
        assertEquals("/participantMode", error.getErrors().get(0).pointer());
        assertEquals(0, mapper.selectById(id).getPublishStatus());
        assertEquals(before.getContractVersion(), mapper.selectById(id).getContractVersion());
        replace(draft(id, "INDIVIDUAL"));
        service.lifecycle().publishAdminActivity(id, 9L); assertEquals(1, mapper.selectById(id).getPublishStatus());
        long version = mapper.selectById(id).getContractVersion();
        var clearing = draft(id, null); clearing.setTitle("must not be written");
        assertThrows(ContractProblemException.class, () -> replace(clearing));
        assertMode(id, 1, "INDIVIDUAL");
        assertEquals(version, mapper.selectById(id).getContractVersion());
        assertEquals("participant regression", mapper.selectById(id).getTitle());
    }
    @Test void legacyUnknownReadAndInvalidInputFailWithoutGuessingOrDefaults() {
        long id = service.drafts().createAdminActivity(draft(null, null));
        jdbc.update("UPDATE activity SET participant_mode=0 WHERE id=?", id);
        assertMode(id, 0, null);
        assertThrows(ContractProblemException.class, () -> service.lifecycle().publishAdminActivity(id, 9L));
        for (int invalid : new int[]{0, 3, 9}) {
            var command = draft(null, null); command.setParticipantMode(invalid);
            assertThrows(BusinessException.class, () -> service.drafts().createAdminActivity(command));
        }
        var unpaired = draft(null, null); unpaired.setCapacity(10);
        assertThrows(BusinessException.class, () -> service.drafts().createAdminActivity(unpaired));
        var mismatched = draft(null, "TEAM"); mismatched.setCapacity(10); mismatched.setCapacityUnit(1);
        assertThrows(BusinessException.class, () -> service.drafts().createAdminActivity(mismatched));
        var platformTeam = draft(null, "TEAM"); platformTeam.setRegistrationMode(2);
        assertThrows(BusinessException.class, () -> service.drafts().createAdminActivity(platformTeam));
    }
    @Test void migrationAllowsSqlNullAndPreservesUpgradeFixtures() {
        var column = jdbc.queryForMap("SELECT IS_NULLABLE,COLUMN_DEFAULT,COLUMN_TYPE,COLUMN_COMMENT FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='activity' AND column_name='participant_mode'");
        assertEquals("YES", column.get("IS_NULLABLE")); assertNull(column.get("COLUMN_DEFAULT"));
        assertEquals("tinyint", column.get("COLUMN_TYPE"));
        for (int mode : new int[]{0, 1, 2, 3}) {
            assertEquals(mode, jdbc.queryForObject("SELECT participant_mode FROM activity WHERE id=?", Integer.class, 93000 + mode));
        }
        assertEquals(1, jdbc.queryForObject("SELECT publish_status FROM activity WHERE id=93000", Integer.class));
        assertEquals(7L, jdbc.queryForObject("SELECT contract_version FROM activity WHERE id=93000", Long.class));
        assertThrows(ContractProblemException.class, () -> service.lifecycle().publishAdminActivity(93000L, 9L));
        assertTrue(jdbc.queryForObject("SELECT COUNT(*)=0 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='activity' AND column_name='participant_mode'", Boolean.class));
        var replay = new JdbcTemplate(new DriverManagerDataSource(System.getProperty("event.test.jdbcUrl")
                .replace("/activity_participant_test", "/activity_participant_replay")
                + "?sslMode=DISABLED&allowPublicKeyRetrieval=true", "root", ""));
        String columns = "SELECT COLUMN_NAME,COLUMN_TYPE,IS_NULLABLE,COLUMN_DEFAULT,EXTRA,COLUMN_COMMENT FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='activity' ORDER BY ORDINAL_POSITION";
        assertEquals(jdbc.queryForList(columns), replay.queryForList(columns));
        String indexes = "SELECT INDEX_NAME,NON_UNIQUE,SEQ_IN_INDEX,COLUMN_NAME,INDEX_TYPE FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='activity' ORDER BY INDEX_NAME,SEQ_IN_INDEX";
        assertEquals(jdbc.queryForList(indexes), replay.queryForList(indexes));
    }
}
