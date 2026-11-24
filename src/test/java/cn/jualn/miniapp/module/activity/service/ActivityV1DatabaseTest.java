package cn.jualn.miniapp.module.activity.service;

import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.common.web.StrongEtag;
import cn.jualn.miniapp.infrastructure.async.job.*;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.module.activity.bo.*;
import cn.jualn.miniapp.module.activity.entity.Activity;
import cn.jualn.miniapp.module.activity.mapper.*;
import cn.jualn.miniapp.module.activity.service.impl.ActivityRegistrationServiceImpl;
import cn.jualn.miniapp.module.admin.operation.service.AdminAuditJournal;
import cn.jualn.miniapp.module.authorization.service.CurrentAuthorization;
import cn.jualn.miniapp.module.eventcontent.service.EventContentService;
import cn.jualn.miniapp.module.media.service.MediaQueries;
import cn.jualn.miniapp.module.notify.service.NotifyService;
import cn.jualn.miniapp.module.subject.service.SubjectExistenceService;
import cn.jualn.miniapp.module.timeline.converter.TimelineConverter;
import cn.jualn.miniapp.module.timeline.mapper.TimelineMapper;
import cn.jualn.miniapp.module.timeline.service.TimelineService;
import cn.jualn.miniapp.module.timeline.service.impl.TimelineServiceImpl;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mapstruct.factory.Mappers;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Owned fresh MySQL schemas; real migrations, SQL, transactions and competing writers.
 * Authorization, audit, Redis and media are explicit doubles. No business DB or provider calls. */
@EnabledIfSystemProperty(named="activity.v1.jdbcUrl", matches="jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/activity_v1_test[0-9]+")
class ActivityV1DatabaseTest {
    static final String FORM = """
            {"fields":[{"fieldKey":"name","label":"姓名","purpose":"NAME","type":"TEXT","required":true,"maxLength":80,"displayOrder":0}],"allowModification":true}
            """;
    static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    static String url;
    JdbcTemplate jdbc;
    DataSourceTransactionManager manager;
    TransactionTemplate tx;
    ActivityMapper activities;
    ActivityRegistrationMapper records;
    ActivityRegistrationService registrations;
    ActivityLifecycleService lifecycle;
    ActivityAdminQueries adminQueries;
    ActivityPublicHistory history;
    TimelineService timeline;
    CurrentAuthorization authorization;
    ActivityReminderCatchUp catchUp;
    EventContentService content;
    ActivityDraftService drafts;
    long id;

    static DriverManagerDataSource source(String target) {
        return new DriverManagerDataSource(target + "?sslMode=DISABLED&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&connectionTimeZone=Asia/Shanghai&forceConnectionTimeZoneToSession=true", "root", "");
    }

    @BeforeAll static void migrateOwnedSchemas() {
        url = System.getProperty("activity.v1.jdbcUrl");
        String schema = url.substring(url.lastIndexOf('/') + 1);
        var server = new JdbcTemplate(source(url.substring(0,url.lastIndexOf('/')) + "/mysql"));
        assertTrue(server.queryForObject("SELECT VERSION()", String.class).startsWith("8.4."));
        assertEquals(System.getProperty("activity.v1.serverUuid"), server.queryForObject("SELECT @@server_uuid", String.class));
        // No IF NOT EXISTS: a prior run is not an acceptable fresh or upgrade fixture.
        server.execute("CREATE DATABASE " + schema + " CHARACTER SET utf8mb4");
        var old = source(url);
        Flyway.configure().dataSource(old).target("83").load().migrate();
        var jdbc = new JdbcTemplate(old);
        jdbc.update("INSERT INTO activity(id,user_id,title,publish_status,form_schema,form_version) VALUES(1001,42,'published title',1,?,'f1'),(1002,42,'private draft',2,?,NULL)", FORM, FORM);
        var upgrade = Flyway.configure().dataSource(old).load();
        upgrade.migrate(); upgrade.validate();
        assertEquals(0, upgrade.migrate().migrationsExecuted);
        assertEquals(0, jdbc.queryForObject("SELECT allow_registration_after_start FROM activity WHERE id=1001", Integer.class));
        assertNotNull(jdbc.queryForObject("SELECT frozen_form_fields FROM activity WHERE id=1001", String.class));
        assertNull(jdbc.queryForObject("SELECT last_public_summary FROM activity WHERE id=1002", String.class));
        assertTrue(jdbc.queryForObject("SELECT last_public_summary FROM activity WHERE id=1001", String.class).contains("published title"));
        server.execute("CREATE DATABASE " + schema + "_fresh CHARACTER SET utf8mb4");
        var replay = Flyway.configure().dataSource(source(url + "_fresh")).load();
        replay.migrate(); replay.validate();
    }

    @BeforeEach void setup() throws Exception {
        var data = source(url);
        jdbc = new JdbcTemplate(data); manager = new DataSourceTransactionManager(data); tx = new TransactionTemplate(manager);
        var config = new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true);
        var factory = new MybatisSqlSessionFactoryBean(); factory.setDataSource(data); factory.setConfiguration(config);
        factory.setGlobalConfig(new com.baomidou.mybatisplus.core.config.GlobalConfig()
                .setDbConfig(new com.baomidou.mybatisplus.core.config.GlobalConfig.DbConfig().setLogicNotDeleteValue("NULL").setLogicDeleteValue("NOW()"))
                .setMetaObjectHandler(new cn.jualn.miniapp.config.MybatisPlusConfig().metaObjectHandler()));
        factory.setMapperLocations(new PathMatchingResourcePatternResolver().getResources("classpath*:mapper/*.xml"));
        var session = new SqlSessionTemplate(factory.getObject());
        if (!config.hasMapper(TimelineMapper.class)) config.addMapper(TimelineMapper.class);
        if (!config.hasMapper(AsyncJobMapper.class)) config.addMapper(AsyncJobMapper.class);
        activities = session.getMapper(ActivityMapper.class); records = session.getMapper(ActivityRegistrationMapper.class);
        timeline = proxy(new TimelineServiceImpl(session.getMapper(TimelineMapper.class), Mappers.getMapper(TimelineConverter.class), mock(SubjectExistenceService.class)));
        history = proxy(new ActivityPublicHistory(activities,timeline,JSON));
        var jobs = proxy(new JobService(session.getMapper(AsyncJobMapper.class),JSON));
        catchUp = proxy(new ActivityReminderCatchUp(jobs,timeline));
        authorization = mock(CurrentAuthorization.class);
        registrations = proxy(new ActivityRegistrationServiceImpl(authorization,activities,records,new ActivityParticipationPolicy(),timeline,history,catchUp));
        content = mock(EventContentService.class);
        when(content.actions(eq(TargetType.ACTIVITY),anyLong())).thenReturn(List.of(cn.jualn.miniapp.module.eventcontent.bo.EventActionBO.builder().actionType(7).actionKey("external").label("external").targetValue("https://example.test/join").sortOrder(0).build()));
        adminQueries = new ActivityAdminQueries(authorization,content,activities,mock(ActivityEnrollmentService.class),mock(MediaQueries.class),timeline);
        var owner = new ActivityLifecycleService(authorization,content,activities,history,timeline,mock(RedisService.class),mock(NotifyService.class),new ActivityParticipationPolicy(),adminQueries);
        org.springframework.test.util.ReflectionTestUtils.setField(owner,"managementAudit",mock(AdminAuditJournal.class));
        lifecycle = proxy(owner);
        drafts = proxy(new ActivityDraftService(authorization,content,activities,history,mock(cn.jualn.miniapp.module.media.service.MediaService.class),timeline,mock(RedisService.class),registrations,lifecycle,adminQueries,mock(AdminAuditJournal.class)));
        jdbc.update("INSERT IGNORE INTO user_profile(id,openid) VALUES(42,'activity-v1-user'),(43,'activity-v1-other')");
        jdbc.update("INSERT INTO activity(user_id,title,summary,category,organizer,location,audience_summary,audience_department_ids,registration_mode,participant_mode,publish_status,lifecycle_status,form_schema,form_version,frozen_form_fields) VALUES(42,'public title','summary',4,'organizer','public room','campus',JSON_ARRAY(),2,1,1,0,?,'f1',JSON_EXTRACT(?,'$.fields'))",FORM,FORM);
        id = jdbc.queryForObject("SELECT MAX(id) FROM activity",Long.class);
        jdbc.update("INSERT INTO timeline(target_type,target_id,node_key,node_type,label,start_time,start_precision,end_precision) VALUES(2,?,'end','REGISTRATION_END','end',NOW(3)+INTERVAL 2 DAY,2,0),(2,?,'main','ACTIVITY_START','main',NOW(3)+INTERVAL 1 DAY,2,0)",id,id);
        UserContext.setUserId(42L);
    }

    @Test void registrationCandidatesFilterLiteralTitlesBeforePagination() throws Exception {
        String title = "literal_%" + id;
        jdbc.update("UPDATE activity SET title=? WHERE id=?", title, id);
        jdbc.update("INSERT INTO activity(user_id,title,publish_status,lifecycle_status) VALUES(42,?,2,1),(42,?,1,0)",
                title, title.replace("_%", "xx"));
        var first = registrations.pageCandidates(9, " " + title + " ", 1, 1);
        assertEquals(2, first.totalItems());
        assertEquals(2, first.items().get(0).publishStatus());
        assertEquals(1, first.items().get(0).lifecycleStatus());
        var second = registrations.pageCandidates(9, title, 2, 1);
        assertEquals(id, second.items().get(0).activityId());
        var empty = registrations.pageCandidates(9, title, Integer.MAX_VALUE, 100);
        assertTrue(empty.items().isEmpty());
        assertEquals(2, empty.totalItems());

        var previous = cn.dev33.satoken.SaManager.getStpInterface();
        cn.dev33.satoken.context.mock.SaTokenContextMockUtil.setMockContext();
        cn.dev33.satoken.SaManager.setStpInterface(new cn.dev33.satoken.stp.StpInterface() {
            public List<String> getPermissionList(Object user, String type) {
                return List.of("activity:registration:read");
            }
            public List<String> getRoleList(Object user, String type) { return List.of(); }
        });
        cn.jualn.miniapp.common.security.AdminStpUtil.STP_LOGIC.switchTo(9L);
        try {
            var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                    new cn.jualn.miniapp.module.activity.controller.admin.AdminActivityRegistrationController(
                            registrations, new cn.jualn.miniapp.module.activity.converter.AdminActivityRegistrationResourceConverter()),
                    new cn.jualn.miniapp.module.activity.controller.admin.ActivityStatisticsController(new ActivityStatisticsQueries(authorization, activities)))
                    .setControllerAdvice(new cn.jualn.miniapp.common.exception.GlobalExceptionHandler()).build();
            String base = "/v1/admin/activities";
            var candidates = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(base + "/registration-candidates")
                    .param("q", title).param("pageSize", "1").accept(org.springframework.http.MediaType.APPLICATION_JSON);
            mvc.perform(candidates)
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control", "no-store"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.totalItems").value(2))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.items[0].publishStatus").value("UNPUBLISHED"));

            doThrow(new cn.jualn.miniapp.common.exception.BusinessException(cn.jualn.miniapp.common.result.ResultCode.FORBIDDEN))
                    .when(authorization).requireActivityRegistrationRead(9L);
            mvc.perform(candidates).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.totalItems").doesNotExist());
            for (String suffix : List.of("/registrations", "/statistics")) {
                mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(base + "/" + id + suffix))
                        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
            }
        } finally {
            cn.dev33.satoken.SaManager.setStpInterface(previous);
            cn.dev33.satoken.context.mock.SaTokenContextMockUtil.clearContext();
        }
    }

    @SuppressWarnings("unchecked") <T> T proxy(T target) {
        var factory = new ProxyFactory(target); factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));
        return (T) factory.getProxy();
    }
    @AfterEach void clearIdentity() { UserContext.clear(); }
    com.fasterxml.jackson.databind.JsonNode answers() { return ActivityFormPolicy.parse("[{\"fieldKey\":\"name\",\"value\":\"001\"}]"); }
    String etag() { return StrongEtag.of("activity",id,activities.selectRegistrationActivity(id).getContractVersion()); }

    @Test void admissionCancellationAndRetryKeepHistoricalSubmittedFact() {
        var r = registrations.submitContract(id,"f1",answers(),null).registration();
        jdbc.update("UPDATE timeline SET start_time=NOW(3)-INTERVAL 1 SECOND WHERE target_id=? AND node_type='ACTIVITY_START'",id);
        assertEquals("/problems/registration-cancellation-closed",assertThrows(ContractProblemException.class,()->registrations.cancelMineContract(id,new RegistrationVersionBO(r.getId(),r.getContractVersion()))).getType());
        assertEquals(1,records.selectById(r.getId()).getStatus());
        jdbc.update("UPDATE activity SET allow_registration_after_start=1 WHERE id=?",id);
        var cancelled=registrations.cancelMineContract(id,new RegistrationVersionBO(r.getId(),r.getContractVersion()));
        jdbc.update("UPDATE activity SET lifecycle_status=1 WHERE id=?",id);
        assertEquals(cancelled.getContractVersion(),registrations.cancelMineContract(id,new RegistrationVersionBO(r.getId(),cancelled.getContractVersion())).getContractVersion());
        assertEquals(412,assertThrows(ContractProblemException.class,()->registrations.cancelMineContract(id,new RegistrationVersionBO(r.getId(),r.getContractVersion()))).getStatus().value());
    }

    @Test void hiddenHistoryUsesLastPublishedFactsAndCursorIsBoundToUserAndFilter() {
        tx.executeWithoutResult(s->history.remember(activities.selectRegistrationActivity(id)));
        var r=registrations.submitContract(id,"f1",answers(),null).registration();
        jdbc.update("UPDATE activity SET publish_status=2,title='private edit',location='secret room' WHERE id=?",id);
        var page=registrations.pageMine(null,null,1);
        assertEquals("public title",page.items().get(0).activity().title());
        assertEquals("public room",page.items().get(0).activity().primaryLocation());
        assertEquals("UNAVAILABLE",page.items().get(0).activity().publicAccess());
        assertTrue(page.items().get(0).registration().getCanCancel());
        assertEquals(r.getId(),registrations.getMine(id).getId());
        var cursor=ActivityRegistrationCursor.encode(42,null,records.selectById(r.getId()));
        assertThrows(ContractProblemException.class,()->registrations.pageMine(cursor,2,1));
        UserContext.setUserId(43L);
        assertTrue(registrations.pageMine(null,null,100).items().stream().noneMatch(item->item.activity().activityId()==id));
        assertThrows(ContractProblemException.class,()->registrations.pageMine(cursor,null,20));
    }

    @Test void deletionHistoryGuardIncludesCancelledAndLegacyRowsAndRestorePreservesLifecycle() {
        var r=registrations.submitContract(id,"f1",answers(),null).registration();
        jdbc.update("UPDATE activity SET publish_status=2 WHERE id=?",id);
        for(int state:List.of(1,2,3)) {
            jdbc.update("UPDATE activity_registration SET status=?,cancelled_at=IF(?=2,NOW(3),NULL),invalidated_at=IF(?=3,NOW(3),NULL),invalidated_by=9,invalid_reason='historical fixture' WHERE id=?",state,state,state,r.getId());
            assertEquals("/problems/activity-has-registration-history",assertThrows(ContractProblemException.class,()->lifecycle.recycle(id,9L,etag(),false)).getType());
        }
        jdbc.update("DELETE FROM activity_registration WHERE activity_id=?",id);
        jdbc.update("UPDATE activity SET lifecycle_status=1 WHERE id=?",id);
        lifecycle.recycle(id,9L,etag(),false);
        var deleted=activities.selectRegistrationActivity(id); assertNotNull(deleted.getDeletedAt()); assertEquals(9L,deleted.getDeletedBy());
        lifecycle.recycle(id,9L,etag(),false);
        assertEquals(deleted.getContractVersion(),activities.selectRegistrationActivity(id).getContractVersion());
        var restored=lifecycle.recycle(id,9L,etag(),true);
        assertNull(restored.getDeletedAt()); assertEquals(2,restored.getPublishStatus()); assertEquals(1,restored.getLifecycleStatus());
    }

    @Test void legacyMappingAndStatisticsDoNotDropRowsOrInventTimes() {
        var r=registrations.submitContract(id,"f1",answers(),null).registration();
        var at=LocalDateTime.of(2026,1,2,3,4,5);
        jdbc.update("UPDATE activity_registration SET status=3,invalidated_at=?,invalidated_by=9,invalid_reason='historical fixture' WHERE id=?",at,r.getId());
        var row=records.pageContract(id,2,0,100).get(0);
        assertEquals(2,row.getStatus());assertEquals(at,row.getCancelledAt());
        assertEquals(1,records.countContract(id,2));assertEquals(1,records.exportContract(id,2).size());
        assertEquals(at,registrations.getMine(id).getCancelledAt());
        var queries=new ActivityStatisticsQueries(authorization,activities);
        var stats=queries.read(id,9);
        assertEquals(0,stats.submittedCount());assertEquals(1,stats.cancelledCount());assertEquals(1,stats.registrationTotal());
        verify(authorization).requireActivityRegistrationRead(9L);
        verify(authorization,never()).require(anyLong(),eq("activity:read"),anyString(),anyString());
    }

    @Test void relationshipAndCatchUpJobRollbackTogether() throws Exception {
        jdbc.update("UPDATE timeline SET start_time=NOW(3)+INTERVAL 30 MINUTE WHERE target_id=? AND node_type='ACTIVITY_START'",id);
        tx.executeWithoutResult(s->{registrations.submitContract(id,"f1",answers(),null);s.setRollbackOnly();});
        assertEquals(0,records.countContract(id,null));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM async_job WHERE job_type='notification.reminder.catch-up' AND subject_id=?",Integer.class,Long.toString(id)));
        registrations.submitContract(id,"f1",answers(),null);
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM async_job WHERE job_type='notification.reminder.catch-up' AND subject_id=?",Integer.class,Long.toString(id)));
        var payload=JSON.readValue(jdbc.queryForObject("SELECT payload FROM async_job WHERE job_type='notification.reminder.catch-up' AND subject_id=?",String.class,Long.toString(id)),cn.jualn.miniapp.module.notify.async.ReminderCatchUpPayload.class);
        assertEquals(id,payload.activityId());assertEquals(42L,payload.userId());
        assertEquals("ACTIVITY_START_REMINDER",payload.rule().notificationType().name());
    }

    AdminActivitySaveBO command() {
        return AdminActivitySaveBO.builder().id(id).operatorId(9L).canonicalFullReplacement(true)
                .title("public title").summary("summary").category(cn.jualn.miniapp.module.activity.enums.ActivityCategory.ACADEMIC_SEMINAR)
                .organizer("organizer").location("public room").audienceSummary("campus").audienceDepartmentIds("[]").contactsJson("[]")
                .registrationMode(2).participantMode(1).formSchema(ActivityFormPolicy.parse(FORM))
                .sections(List.of()).actions(List.of()).attachmentLinks(List.of())
                .timelineItems(timeline.listTimelinesByTarget(TargetType.ACTIVITY,id).stream().map(n->cn.jualn.miniapp.module.timeline.bo.TimelineItemBO.builder()
                        .nodeKey(n.getNodeKey()).nodeType(n.getNodeType()).label(n.getLabel()).startTime(n.getStartTime()).endTime(n.getEndTime())
                        .startPrecision(n.getStartPrecision()).endPrecision(n.getEndPrecision()).sortOrder(n.getSortOrder()).build()).toList()).build();
    }

    @Test void completeSavePersistsContinuityAndFirstPublicationFreezesOnlyFields() {
        jdbc.update("UPDATE activity SET publish_status=0,form_version=NULL,frozen_form_fields=NULL WHERE id=?",id);
        var command=command();command.setAllowRegistrationAfterStart(true);
        drafts.updateAdminActivity(command);
        assertTrue(activities.selectRegistrationActivity(id).getAllowRegistrationAfterStart());
        lifecycle.publishAdminActivity(id,9L);
        String version=activities.selectRegistrationActivity(id).getFormVersion();assertNotNull(version);
        var changed=command();((com.fasterxml.jackson.databind.node.ObjectNode)changed.getFormSchema().path("fields").get(0)).put("label","changed");
        assertEquals("/problems/form-locked",assertThrows(ContractProblemException.class,()->drafts.updateAdminActivity(changed)).getType());
        var modification=command();((com.fasterxml.jackson.databind.node.ObjectNode)modification.getFormSchema()).put("allowModification",false);
        drafts.updateAdminActivity(modification);
        var current=activities.selectRegistrationActivity(id);
        assertFalse(current.getAllowRegistrationAfterStart());assertEquals(version,current.getFormVersion());
        assertFalse(ActivityFormPolicy.parse(current.getFormSchema()).path("allowModification").asBoolean());
        registrations.submitContract(id,version,answers(),null);
        modification.setRegistrationMode(4);drafts.updateAdminActivity(modification);
        assertEquals(4,activities.selectRegistrationActivity(id).getRegistrationMode());
        modification.setRegistrationMode(3);modification.setFormSchema(null);
        assertEquals("/problems/registration-configuration-conflict",assertThrows(ContractProblemException.class,()->drafts.updateAdminActivity(modification)).getType());
    }

    @Test void expiredMainRangeCannotBeReopenedAndRepublishUsesMostRecentPublishedAt() {
        jdbc.update("UPDATE timeline SET start_time=NOW(3)-INTERVAL 2 DAY,end_time=NOW(3)-INTERVAL 1 DAY,end_precision=2 WHERE target_id=? AND node_type='ACTIVITY_START'",id);
        var command=command();command.setTitle("reopened");
        command.getTimelineItems().stream().filter(n->n.getNodeType().equals("ACTIVITY_START")).forEach(n->{n.setStartTime(LocalDateTime.now().plusDays(2));n.setEndTime(LocalDateTime.now().plusDays(3));});
        assertEquals("/problems/state-conflict",assertThrows(ContractProblemException.class,()->drafts.updateAdminActivity(command)).getType());
        assertEquals("public title",activities.selectRegistrationActivity(id).getTitle());
        assertEquals("ENDED",ActivityStagePolicy.state(0,timeline.listTimelinesByTarget(TargetType.ACTIVITY,id),LocalDateTime.now(ZoneId.of("Asia/Shanghai"))));
        jdbc.update("UPDATE activity SET publish_status=2,published_at=NOW(3)-INTERVAL 5 DAY WHERE id=?",id);
        lifecycle.publishAdminActivity(id,9L);
        var published=activities.selectAdminActivityById(id).getPublishedAt();
        assertTrue(published.isAfter(LocalDateTime.now(ZoneId.of("Asia/Shanghai")).minusMinutes(1)));
        lifecycle.publishAdminActivity(id,9L);assertEquals(published,activities.selectAdminActivityById(id).getPublishedAt());
    }

    @Test void publicationErrorsIdentifyFieldsAndCapacityConflictIsStructured() {
        jdbc.update("UPDATE activity SET publish_status=0,summary=NULL WHERE id=?",id);
        String before=etag();
        var missing=assertThrows(ContractProblemException.class,()->lifecycle.publishAdminActivity(id,9L));
        assertEquals("/problems/publish-validation-failed",missing.getType());
        assertEquals("/summary",missing.getErrors().get(0).pointer());assertEquals(before,etag());
        jdbc.update("UPDATE activity SET summary='summary' WHERE id=?",id);
        jdbc.update("UPDATE timeline SET start_precision=0,start_time=NULL,time_description='TBD' WHERE target_id=? AND node_type='REGISTRATION_END'",id);
        var window=assertThrows(ContractProblemException.class,()->lifecycle.publishAdminActivity(id,9L));
        assertEquals("/problems/publish-validation-failed",window.getType());assertEquals("/timeline",window.getErrors().get(0).pointer());
        jdbc.update("UPDATE timeline SET start_precision=2,time_description=NULL,start_time=NOW(3)+INTERVAL 2 DAY WHERE target_id=? AND node_type='REGISTRATION_END'",id);
        lifecycle.publishAdminActivity(id,9L);
        registrations.submitContract(id,"f1",answers(),null);
        UserContext.setUserId(43L);registrations.submitContract(id,"f1",answers(),null);UserContext.setUserId(42L);
        var command=command();command.setCapacity(1);command.setCapacityUnit(1);
        assertEquals("/problems/registration-configuration-conflict",assertThrows(ContractProblemException.class,()->drafts.updateAdminActivity(command)).getType());
        assertNull(activities.selectRegistrationActivity(id).getCapacity());
    }

    @Test void personalHistoryHttpKeepsPrivacyNoStoreAndPerRecordEtag() throws Exception {
        tx.executeWithoutResult(s->history.remember(activities.selectRegistrationActivity(id)));
        registrations.submitContract(id,"f1",answers(),null);
        jdbc.update("UPDATE activity SET publish_status=2,title='private edit',location='secret room' WHERE id=?",id);
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                new cn.jualn.miniapp.module.activity.controller.MyActivityRegistrationsController(registrations,new cn.jualn.miniapp.module.activity.converter.PersonalRegistrationConverter()))
                .setControllerAdvice(new cn.jualn.miniapp.common.exception.GlobalExceptionHandler()).build();
        var response=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/v1/users/me/activity-registrations").accept(org.springframework.http.MediaType.APPLICATION_JSON))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control","no-store")).andReturn().getResponse();
        var body=JSON.readTree(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("public title",body.path("items").get(0).path("activity").path("title").asText());
        assertTrue(body.path("items").get(0).path("registrationEtag").asText().startsWith("\"activity-registration-"));
        assertFalse(body.toString().contains("secret room"));assertFalse(body.toString().contains("private edit"));
    }

    @Test void adminHttpUsesIndependentStatisticsPermissionAndStrongDeleteRestoreEtag() throws Exception {
        var previous=cn.dev33.satoken.SaManager.getStpInterface();
        var edit=new boolean[]{false};
        cn.dev33.satoken.context.mock.SaTokenContextMockUtil.setMockContext();
        cn.dev33.satoken.SaManager.setStpInterface(new cn.dev33.satoken.stp.StpInterface() {
            public List<String> getPermissionList(Object user,String type) {return edit[0] ? List.of("activity:registration:read","activity:edit") : List.of("activity:registration:read");}
            public List<String> getRoleList(Object user,String type) {return List.of();}
        });
        cn.jualn.miniapp.common.security.AdminStpUtil.STP_LOGIC.switchTo(9L);
        try {
            var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                    new cn.jualn.miniapp.module.activity.controller.admin.ActivityStatisticsController(new ActivityStatisticsQueries(authorization,activities)),
                    new cn.jualn.miniapp.module.activity.controller.admin.CanonicalAdminActivityLifecycleController(lifecycle,new cn.jualn.miniapp.module.activity.converter.AdminActivityConverter()))
                    .setControllerAdvice(new cn.jualn.miniapp.common.exception.GlobalExceptionHandler()).build();
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/v1/admin/activities/{id}/statistics",id).accept(org.springframework.http.MediaType.APPLICATION_JSON))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.registrationTotal").value(0));
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/v1/admin/activities/{id}",id).header("If-Match",etag()))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
            edit[0]=true;
            jdbc.update("UPDATE activity SET publish_status=2 WHERE id=?",id);
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/v1/admin/activities/{id}",id))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isPreconditionRequired());
            var deleted=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/v1/admin/activities/{id}",id).header("If-Match",etag()))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNoContent())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control","no-store")).andReturn().getResponse().getHeader("ETag");
            assertEquals(etag(),deleted);
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/v1/admin/activities/{id}",id).header("If-Match",deleted))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNoContent())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("ETag",deleted));
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/v1/admin/activities/{id}:restore",id).accept(org.springframework.http.MediaType.APPLICATION_JSON).header("If-Match",deleted))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.publishStatus").value("UNPUBLISHED"));
        } finally {
            cn.dev33.satoken.SaManager.setStpInterface(previous);
            cn.dev33.satoken.context.mock.SaTokenContextMockUtil.clearContext();
        }
    }

    @Test void historyCursorCarriesTheFrozenOrderingTupleInsteadOfRereadingItsAnchor() {
        jdbc.update("INSERT IGNORE INTO user_profile(id,openid) VALUES(55,'cursor-test-user')");
        UserContext.setUserId(55L);
        registrations.submitContract(id,"f1",answers(),null);
        jdbc.update("UPDATE activity_registration SET submitted_at='2098-01-01 10:00:00.000' WHERE activity_id=?",id);
        jdbc.update("INSERT INTO activity(user_id,title,publish_status,form_schema,form_version) VALUES(42,'second activity',1,?,'f1')",FORM);
        long second=jdbc.queryForObject("SELECT MAX(id) FROM activity",Long.class);
        jdbc.update("INSERT INTO activity_registration(activity_id,user_id,form_data,form_version,status,submitted_at) VALUES(?,55,?,'f1',1,'2098-01-01 09:00:00.000')",second,"{\"name\":\"001\"}");
        var first=registrations.pageMine(null,null,1);assertEquals(id,first.items().get(0).activity().activityId());assertNotNull(first.nextCursor());
        jdbc.update("UPDATE activity_registration SET submitted_at='2098-01-01 08:00:00.000' WHERE activity_id=?",id);
        var next=registrations.pageMine(first.nextCursor(),null,1);
        assertEquals(second,next.items().get(0).activity().activityId());
    }

    @Test void deletionAndSubmissionUseTheSameActivityRowLock() throws Exception {
        var locked=new CountDownLatch(1);var release=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            var deleting=pool.submit(()->tx.execute(s->{activities.lockRegistrationActivity(id);locked.countDown();try{assertTrue(release.await(5,TimeUnit.SECONDS));}catch(InterruptedException e){throw new IllegalStateException(e);}activities.recycle(id,9L,false);return null;}));
            assertTrue(locked.await(5,TimeUnit.SECONDS));
            var submitting=pool.submit(()->{UserContext.setUserId(43L);try{return assertThrows(ContractProblemException.class,()->registrations.submitContract(id,"f1",answers(),null)).getType();}finally{UserContext.clear();}});
            assertThrows(TimeoutException.class,()->submitting.get(150,TimeUnit.MILLISECONDS));
            release.countDown();deleting.get(5,TimeUnit.SECONDS);
            assertEquals("/problems/registration-not-open",submitting.get(5,TimeUnit.SECONDS));
            assertEquals(0,records.countContract(id,null));
        } finally {release.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
    }
}
