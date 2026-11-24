package cn.jualn.miniapp.module.admin.operation;

import cn.jualn.miniapp.module.authorization.service.CurrentAuthorization;
import cn.jualn.miniapp.module.authorization.service.RbacCursorCodec;
import cn.jualn.miniapp.module.authorization.model.RbacModels;

import cn.jualn.miniapp.module.admin.operation.bo.AdminAuditBO.*;
import cn.jualn.miniapp.module.admin.operation.service.*;
import cn.jualn.miniapp.module.admin.operation.mapper.AdminAuditMapper;
import cn.jualn.miniapp.module.content.mapper.*;
import cn.jualn.miniapp.module.content.bo.AdminPostProjection.*;
import cn.jualn.miniapp.module.content.service.AdminQaContextReader;
import cn.jualn.miniapp.module.person.mapper.ManagedPersonMapper;
import cn.jualn.miniapp.module.person.service.profile.SubmissionNameReader;
import cn.jualn.miniapp.common.exception.ContractProblemException;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.*;
import javax.sql.DataSource;
import java.time.*;
import java.util.*;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real isolated MySQL/Flyway/MyBatis/Spring transactions. ROOT identity is an explicit synthetic adapter. */
@EnabledIfSystemProperty(named="admin.business.test.jdbcUrl",matches="jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/admin_business_test_[a-z0-9]+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdminBusinessContractDatabaseTest {
    static DataSource source;
    AnnotationConfigApplicationContext context;JdbcTemplate jdbc;TransactionTemplate tx;AdminAuditJournal journal;AdminAuditQueries queries;
    final LocalDateTime at=LocalDateTime.of(2026,10,30,2,0,0,123456000);
    static boolean root=true;
    static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    @BeforeAll void start() throws Exception {
        String url=System.getProperty("admin.business.test.jdbcUrl");String schema=url.substring(url.lastIndexOf('/')+1);
        var admin=new JdbcTemplate(new DriverManagerDataSource(url.substring(0,url.lastIndexOf('/'))+"/mysql","root",""));
        assertEquals(System.getProperty("admin.business.test.serverUuid"),admin.queryForObject("SELECT @@server_uuid",String.class));
        int existing=admin.queryForObject("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",Integer.class,schema);
        if(existing==0)admin.execute("CREATE DATABASE "+schema);else assertTrue(Boolean.getBoolean("admin.business.test.reuseSchema"),"Existing schema requires explicit task-owned reuse");
        source=new DriverManagerDataSource(url+"?sslMode=DISABLED&allowPublicKeyRetrieval=true&connectionTimeZone=%2B08:00&forceConnectionTimeZoneToSession=true","root","");
        var flyway=Flyway.configure().dataSource(source).load();flyway.migrate();flyway.validate();
        context=new AnnotationConfigApplicationContext(Config.class);jdbc=new JdbcTemplate(source);
        jdbc.update("INSERT IGNORE INTO user_profile(id,openid,nickname) VALUES(1,'audit-actor','Actor'),(991,'admin-business-test','Synthetic')");
        tx=new TransactionTemplate(context.getBean(org.springframework.transaction.PlatformTransactionManager.class));
        journal=context.getBean(AdminAuditJournal.class);queries=context.getBean(AdminAuditQueries.class);
    }
    @AfterAll void close() {if(context!=null)context.close();}
    @BeforeEach void reset() {root=true;jdbc.update("DELETE FROM admin_audit_record");}
    @Test void reliableAcceptanceRollsBackWithOwnerAndKeepsMicros() {
        assertThrows(IllegalStateException.class,()->tx.execute(s->{journal.operation("rollback",1,"publishArticle","ARTICLE","91",at);throw new IllegalStateException();}));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit_record",Integer.class));
        tx.execute(s->{journal.operation("committed",1,"publishArticle","ARTICLE","91",at);return null;});
        var page=queries.page(query("OPERATION",null,null,20),null);
        assertEquals(1,page.items().size());assertEquals("2026-10-30T02:00:00.123456Z",page.items().get(0).path("occurredAt").asText());
        assertFalse(page.items().get(0).has("request"));assertEquals("PARTIAL",page.coverage().stream().filter(c->"publishArticle".equals(c.source())).findFirst().orElseThrow().status());
        assertTrue(page.coverage().stream().anyMatch(c->"UNAVAILABLE".equals(c.status())));
        assertThrows(Exception.class,()->journal.operation("outside",1,"publishArticle","ARTICLE","91",at));
    }
    @Test void cursorPinsHighWaterAndRootIsRechecked() {
        tx.execute(s->{for(String id:List.of("a","b","c"))journal.operation(id,1,"publishArticle","ARTICLE","91",at);return null;});
        var first=queries.page(query("OPERATION",null,null,1),null);assertNotNull(first.nextCursor());
        tx.execute(s->{journal.operation("z-new",1,"publishArticle","ARTICLE","91",at);return null;});
        var next=queries.page(query("OPERATION",null,null,1),first.nextCursor());
        assertNotEquals(first.items().get(0).path("eventId"),next.items().get(0).path("eventId"));
        assertNotEquals("O:z-new",next.items().get(0).path("eventId").asText());
        assertEquals("/problems/invalid-cursor",assertThrows(ContractProblemException.class,()->queries.page(query("OPERATION",null,"different",1),first.nextCursor())).getType());
        var original=query("OPERATION",null,null,1);
        assertEquals("/problems/invalid-cursor",assertThrows(ContractProblemException.class,()->queries.page(new Query("OPERATION",1,original.after().plusNanos(1000),original.before(),null,null,null,null,null,null,null,null,1),first.nextCursor())).getType());
        root=false;assertEquals(org.springframework.http.HttpStatus.FORBIDDEN,assertThrows(ContractProblemException.class,()->queries.page(query("OPERATION",null,null,1),first.nextCursor())).getStatus());
    }
    @Test void dutyExpiryIsAppendOnlyAndAccountHistoryDoesNotMoveToPerson() {
        tx.execute(s->{journal.duty("grant","group",1,"ROLE_ASSIGNMENT","GRANT","22",null,"ROLE_ASSIGNMENT","assignment",null,
            journal.tree(Map.of("status","ACTIVE","roleCode","READER","expiresAt","2026-10-30T02:00:01Z")),at);
            journal.expireRecordedFacts(at.plusSeconds(2));journal.expireRecordedFacts(at.plusSeconds(3));return null;});
        var page=queries.page(query("DUTY","22",null,20),null);assertEquals(2,page.items().size());
        assertEquals("EXPIRE",page.items().get(0).path("action").asText());assertEquals("SYSTEM",page.items().get(0).path("actor").path("type").asText());
        assertEquals(7,page.coverage().size());assertTrue(page.coverage().stream().noneMatch(c->"COMPLETE".equals(c.status())));
        assertThrows(ContractProblemException.class,()->queries.page(new Query("DUTY",1,at.minusDays(32),at.plusSeconds(1),null,"22",null,null,null,null,null,null,20),null));
    }
    @Test void dependencyEndInvalidatesOriginalDutyWithoutRevival() {
        tx.execute(s->{journal.dutyWithReferences("scope","group",1,"DUTY","GRANT","22","person-original","DUTY_CHANGE","scope",null,
            journal.tree(Map.of("status","ACTIVE","dutyCode","POST_MODERATE")),at,List.of(new Reference("PERSON_BINDING","binding-original")));
            journal.duty("end-binding","end-group",1,"BINDING","INVALIDATE","22","person-original","PERSON_BINDING","binding-original",
            journal.tree(Map.of("status","ACTIVE")),journal.tree(Map.of("status","ENDED")),at.plusSeconds(1));return null;});
        var page=queries.page(query("DUTY","22",null,20),null);
        assertEquals(3,page.items().size());assertTrue(page.items().stream().anyMatch(n->"DUTY".equals(n.path("source").asText()) && "INVALIDATE".equals(n.path("action").asText())));
    }
    @Test void qaNodesHideDeletedOrDifferentThreadBodies() {
        jdbc.update("DELETE FROM comment WHERE id=991");jdbc.update("DELETE FROM post WHERE id=991");

        jdbc.update("INSERT INTO post(id,user_id,title,content,post_type,status,category_id) VALUES(991,991,'question','question body','MENTOR_QA',2,1)");
        jdbc.update("INSERT INTO comment(id,user_id,target_type,target_id,content,status,mentor_evidence) VALUES(991,991,1,991,'answer body',1,'{}')");
        var qa=context.getBean(AdminQaContextReader.class);var visible=qa.safety(new Relation(991L,991L,null));
        assertEquals("AVAILABLE",visible.question().availability());assertEquals("AVAILABLE",visible.answerRoot().availability());
        jdbc.update("UPDATE comment SET deleted_at=NOW() WHERE id=991");
        var hidden=qa.safety(new Relation(991L,991L,992L));assertEquals("UNAVAILABLE",hidden.answerRoot().availability());
        assertNull(hidden.answerRoot().id());assertNull(hidden.replyTo().contentPreview());
    }
    @Test void submissionNameUsesOriginalBindingEvenAfterReassociation() {
        jdbc.update("DELETE FROM person_account_binding WHERE id='name-binding'");jdbc.update("DELETE FROM person_profile WHERE id='name-person'");jdbc.update("DELETE FROM person_foundation_event WHERE id='event'");
        jdbc.update("INSERT INTO person_foundation_event(id,aggregate_type,aggregate_id,aggregate_version,operation,after_json,operator_user_id,recorded_at,reason,verification_reference,source,receipt_id) VALUES('event','PROFILE','name-person',1,'CREATE','{}',1,UTC_TIMESTAMP(6),'test','test','LOCAL_MANUAL','receipt')");
        jdbc.update("INSERT INTO person_profile(id,real_name,status,revision,binding_revision,created_at,updated_at,last_change_id) VALUES('name-person','Original','ACTIVE',1,1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),'event')");
        jdbc.update("INSERT INTO person_account_binding(id,user_id,person_id,status,revision,started_at,started_by,verification_method,source_reference,ended_at,ended_by,end_reason) VALUES('name-binding',991,'name-person','ENDED',2,'2026-10-29 00:00:00',1,'ADMIN_MANUAL','test','2026-10-30 03:00:00',1,'test')");
        jdbc.update("UPDATE person_account_binding SET ended_at='2026-10-30 03:00:00',ended_by=1,end_reason='test' WHERE id='name-binding'");
        var reader=context.getBean(SubmissionNameReader.class);
        assertEquals("Original",reader.name("name-person","name-binding",991,at));
        assertNull(reader.name("other-person","name-binding",991,at));assertNull(reader.name("name-person","name-binding",992,at));
        assertNull(reader.name("name-person","name-binding",991,at.plusHours(2)));
    }
    @Test void managedPostFiltersRunBeforePagingAndKeepNamespaceBoundary() {
        jdbc.update("DELETE FROM post_topic WHERE post_id IN (992,993)");jdbc.update("DELETE FROM post WHERE id IN (992,993)");
        jdbc.update("INSERT INTO post(id,user_id,title,content,post_type,status,category_id) VALUES(992,991,'normal','normal body','NORMAL',2,NULL),(993,991,'qa','qa body','MENTOR_QA',2,1)");
        var mapper=context.getBean(AdminContentMapper.class);
        var qa=mapper.selectPage(null,null,"post",null,null,"latest",null,null,null,null,"MENTOR_QA",1L,null,10,List.of("POST"));
        assertTrue(qa.stream().allMatch(row->"MENTOR_QA".equals(row.getPostType()) && Long.valueOf(1).equals(row.getCategoryId())));
        assertTrue(qa.stream().anyMatch(row->row.getId()==993));assertTrue(qa.stream().noneMatch(row->row.getId()==992));
        assertTrue(mapper.selectPage(null,null,"post",null,null,"latest",null,null,null,null,"MENTOR_QA",1L,null,10,List.of("ACTIVITY")).isEmpty());
    }
    @Test void failureProjectionDistinguishesRollbackAndUnknownCommit() {
        var sink=context.getBean(AdminAuditAttemptSink.class);
        var request=new org.springframework.mock.web.MockHttpServletRequest();
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(new org.springframework.web.context.request.ServletRequestAttributes(request));
        try {
            assertThrows(IllegalArgumentException.class,()->tx.execute(status->{journal.confirmed(1,"replaceArticle","ARTICLE","91");throw new IllegalArgumentException();}));
            @SuppressWarnings("unchecked") var attempts=(List<AdminAuditAttemptContext.Attempt>)request.getAttribute(AdminAuditAttemptContext.ATTRIBUTE);
            sink.accept(attempts.get(0),400);
            var unknown=AdminAuditAttemptContext.confirmed(1,"publishArticle","ARTICLE","92");sink.accept(unknown,500);
            var page=queries.page(query("OPERATION",null,null,20),null);
            assertTrue(page.items().stream().anyMatch(n->"REJECTED".equals(n.path("result").asText())));
            assertTrue(page.items().stream().anyMatch(n->"UNKNOWN".equals(n.path("result").asText())));
        } finally {org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();}
    }
    @Test void httpBoundaryUsesDirectPagesNoStoreAndExactFailureTypes() throws Exception {
        var tokens=mock(cn.jualn.miniapp.module.admin.auth.service.AdminTokenService.class);when(tokens.requireAuthenticatedSession()).thenReturn(1L);
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new cn.jualn.miniapp.module.admin.operation.controller.AdminAuditController(tokens,queries))
            .setControllerAdvice(new cn.jualn.miniapp.module.admin.operation.controller.AdminAuditAdvice(new cn.jualn.miniapp.common.exception.GlobalExceptionHandler()))
            .addFilters(new cn.jualn.miniapp.module.admin.operation.controller.AdminAuditNoStoreFilter()).build();
        var request=org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/v1/admin/authorization/duty-history")
            .param("occurredAfter","2026-10-29T00:00:00Z").param("occurredBefore","2026-10-31T00:00:00Z").param("userId","22");
        mvc.perform(request.accept("application/json")).andDo(result->java.nio.file.Files.writeString(java.nio.file.Path.of("target/admin-audit-http.json"),result.getResponse().getContentAsString())).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control","no-store"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.coverage.length()").value(7))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data").doesNotExist());
        root=false;
        mvc.perform(request).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.type").value("/problems/forbidden"));
        when(tokens.requireAuthenticatedSession()).thenThrow(new cn.jualn.miniapp.common.exception.BusinessException(cn.jualn.miniapp.common.result.ResultCode.UNAUTHORIZED));
        mvc.perform(request).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control","no-store"));
    }
    @Test void utcOwnerStateProjectionRetainsPrecisionAndHasOffsetOnBothBounds() {
        tx.execute(status->{journal.personFact("utc-qualification","group",1,"changeMentorQualification","MENTOR","person","person",null,
            "{\"qualificationStatus\":\"ACTIVE\",\"validFrom\":\"2026-10-30T02:00:00.123456\",\"validUntil\":\"2026-11-01T10:00:00.654321+08:00\"}",at);return null;});
        var page=queries.page(new Query("DUTY",1,at.minusDays(1),at.plusDays(1),"person",null,null,null,null,null,null,null,20),null);
        assertEquals("2026-10-30T02:00:00.123456Z",page.items().get(0).path("after").path("validFrom").asText());
        assertEquals("2026-11-01T02:00:00.654321Z",page.items().get(0).path("after").path("expiresAt").asText());
    }
    @Test void safetyRoundRetainsItsFixedRelationAcrossLifecycleUpdates() throws Exception {
        long task=java.util.concurrent.ThreadLocalRandom.current().nextLong(1000000,Long.MAX_VALUE);
        jdbc.update("INSERT INTO content_audit_log(id,target_type,target_id,audit_source,wx_result,final_result) VALUES(?,1,994,1,1,2)",task);
        var mapper=context.getBean(cn.jualn.miniapp.module.audit.mapper.ContentSafetyReviewMapper.class);
        var round=new cn.jualn.miniapp.module.audit.entity.ContentSafetyReview();round.setTaskId(task);round.setTargetType(1);round.setTargetId(994L);
        round.setSubmissionVersion(java.util.UUID.randomUUID().toString());round.setScene("post");round.setResourceNamespace("POST");round.setBusinessRevision(1L);
        round.setSnapshotTitle("fixed question");round.setSnapshotContent("fixed evidence");round.setSnapshotAttachments("[]");
        var fixed=JSON.valueToTree(new Relation(994L,995L,996L));round.setSnapshotQaRelation(fixed.toString());round.setExpectedChecks(1);
        round.setState("checking");round.setCreatedAt(at);mapper.insert(round);
        assertEquals(new Relation(994L,995L,996L),JSON.readValue(mapper.find(task).getSnapshotQaRelation(),Relation.class));
        round.setState("pending");round.setSnapshotQaRelation(JSON.writeValueAsString(new Relation(1L,2L,3L)));mapper.save(round);
        assertEquals(new Relation(994L,995L,996L),JSON.readValue(mapper.find(task).getSnapshotQaRelation(),Relation.class));
        round.setSnapshotQaRelation(null);
        var owner=new cn.jualn.miniapp.module.audit.service.ContentSafetyReviewService(mapper,JSON,context.getBean(AdminQaContextReader.class),null,null,null);
        assertNull(owner.qaContext(round));
    }
    Query query(String kind,String user,String operation,int size) {return new Query(kind,1,at.minusDays(1),at.plusDays(1),null,user,null,null,operation,null,null,null,size);}
    @Configuration @EnableTransactionManagement @Import({AdminAuditJournal.class,AdminAuditAttemptSink.class,AdminAuditQueries.class,AdminQaContextReader.class,SubmissionNameReader.class,RbacCursorCodec.class})
    static class Config {
        @Bean DataSource source() {return source;}
        @Bean org.springframework.transaction.PlatformTransactionManager tx() {return new DataSourceTransactionManager(source);}
        @Bean ObjectMapper json() {return JSON;}
        @Bean SqlSessionFactory sql() throws Exception {
            var config=new MybatisConfiguration();config.setMapUnderscoreToCamelCase(true);
            var factory=new MybatisSqlSessionFactoryBean();factory.setDataSource(source);factory.setConfiguration(config);
            factory.setMapperLocations(new ClassPathResource("mapper/AdminAuditMapper.xml"),new ClassPathResource("mapper/AdminContentMapper.xml"),new ClassPathResource("mapper/ManagedPersonMapper.xml"));
            return factory.getObject();
        }
        @Bean MapperFactoryBean<cn.jualn.miniapp.module.audit.mapper.ContentSafetyReviewMapper> safety(SqlSessionFactory sql) {return mapper(cn.jualn.miniapp.module.audit.mapper.ContentSafetyReviewMapper.class,sql);}
        @Bean MapperFactoryBean<AdminAuditMapper> audit(SqlSessionFactory sql) {return mapper(AdminAuditMapper.class,sql);}
        @Bean MapperFactoryBean<AdminContentMapper> content(SqlSessionFactory sql) {return mapper(AdminContentMapper.class,sql);}
        @Bean MapperFactoryBean<ManagedPersonMapper> people(SqlSessionFactory sql) {return mapper(ManagedPersonMapper.class,sql);}
        static <T> MapperFactoryBean<T> mapper(Class<T> type,SqlSessionFactory sql) {var factory=new MapperFactoryBean<T>(type);factory.setSqlSessionFactory(sql);return factory;}
        @Bean CurrentAuthorization authorization() {
            var auth=mock(CurrentAuthorization.class);
            when(auth.current(anyLong())).thenAnswer(i->{var grant=new RbacModels.Grant("root","ROOT","AUTHORIZATION","GLOBAL",List.of("authorization:root-manage"),null);
                return new RbacModels.Decision(i.getArgument(0),RbacModels.Source.ASSIGNMENT,"v","catalog",LocalDateTime.now(),true,List.of(),root?List.of(grant):List.of());});
            return auth;
        }
    }
}
