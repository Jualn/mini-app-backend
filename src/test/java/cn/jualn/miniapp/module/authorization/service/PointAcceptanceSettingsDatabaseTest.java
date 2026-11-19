package cn.jualn.miniapp.module.authorization.service;

import cn.jualn.miniapp.module.authorization.service.PlatformDutyPolicy;
import cn.jualn.miniapp.module.authorization.service.RbacAuthorizationReader;
import cn.jualn.miniapp.module.authorization.mapper.RbacMapper;
import cn.jualn.miniapp.module.authorization.model.RbacModels;
import cn.jualn.miniapp.module.authorization.service.TransactionalAuthorization;
import cn.jualn.miniapp.module.authorization.service.UnifiedDutyReader;


import cn.jualn.miniapp.module.person.service.facts.PersonCatalog;
import cn.jualn.miniapp.module.person.service.access.PersonBusinessScopes;
import cn.jualn.miniapp.module.person.service.access.PersonAuthorityGate;

import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.common.exception.GlobalExceptionHandler;
import cn.jualn.miniapp.module.person.mapper.PersonMapper;
import cn.jualn.miniapp.module.person.service.access.PersonAuthentication;
import cn.jualn.miniapp.module.points.bo.PointAcceptanceSettings.Decision;
import cn.jualn.miniapp.module.points.controller.PointAcceptanceSettingsController;
import cn.jualn.miniapp.module.points.controller.PointsProblemAdvice;
import cn.jualn.miniapp.module.points.mapper.PointAcceptanceSettingsMapper;
import cn.jualn.miniapp.module.points.mapper.PointsMapper;
import cn.jualn.miniapp.module.points.service.PointAcceptanceSettingsService;
import cn.jualn.miniapp.module.points.service.PointsAcceptancePolicy;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Fresh owned schema; real Flyway, MyBatis, Spring transactions and authority locks.
 * Session/capability witnesses are explicit synthetic adapters; MVC uses MockMvc, not a live Provider. */
@EnabledIfSystemProperty(named="managed.person.test.jdbcUrl", matches="jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/independent_person_test_[a-z0-9]+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PointAcceptanceSettingsDatabaseTest {
    AnnotationConfigApplicationContext context;
    JdbcTemplate db;
    PointAcceptanceSettingsService service;
    PersonAuthentication authentication;
    RbacAuthorizationReader reader;
    MockMvc mvc;
    static DataSource source(String url) {
        return new DriverManagerDataSource(url+"?sslMode=DISABLED&allowPublicKeyRetrieval=true&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
                Objects.requireNonNull(System.getenv("MANAGED_PERSON_TEST_DB_USER")), Objects.requireNonNull(System.getenv("MANAGED_PERSON_TEST_DB_PASSWORD")));
    }
    @BeforeAll void start() throws Exception {
        String url=System.getProperty("managed.person.test.jdbcUrl"), schema=url.substring(url.lastIndexOf('/')+1);
        var admin=new JdbcTemplate(source(url.substring(0,url.lastIndexOf('/'))+"/mysql"));
        assertEquals(System.getProperty("managed.person.test.serverUuid"),admin.queryForObject("SELECT @@server_uuid",String.class));
        assertEquals(0,admin.queryForObject("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",Integer.class,schema));
        admin.execute("CREATE DATABASE "+schema);
        var flyway=Flyway.configure().dataSource(source(url)).load();flyway.migrate();flyway.validate();
        context=new AnnotationConfigApplicationContext(Config.class);db=new JdbcTemplate(context.getBean(DataSource.class));
        db.update("INSERT INTO user_profile(id,openid) VALUES(7001,'acceptance-synthetic-operator')");
        db.update("INSERT INTO rbac_authorization_authority(user_id,source,version,updated_at) VALUES(7001,'ASSIGNMENT',?,UTC_TIMESTAMP(6))",UUID.randomUUID().toString());
        service=context.getBean(PointAcceptanceSettingsService.class);authentication=context.getBean(PersonAuthentication.class);reader=context.getBean(RbacAuthorizationReader.class);
        mvc=MockMvcBuilders.standaloneSetup(new PointAcceptanceSettingsController(service,authentication))
                .setControllerAdvice(new PointsProblemAdvice(new GlobalExceptionHandler())).build();
    }
    @AfterAll void stop() {if(context!=null)context.close();}
    @BeforeEach void witnesses() {
        reset(authentication,reader);when(authentication.actor()).thenReturn(7001L);
        var root=new RbacModels.Grant("root","ROOT","AUTHORIZATION","GLOBAL",List.of("authorization:root-manage"),null);
        var owner=new RbacModels.OwnerCapability("root","POINTS","GLOBAL",List.of("points:acceptance-manage"));
        var decision=new RbacModels.Decision(7001,RbacModels.Source.ASSIGNMENT,"v","c",LocalDateTime.now(ZoneOffset.UTC),true,List.of(),List.of(root),List.of(owner));
        when(reader.readCurrent(eq(7001L),any())).thenReturn(decision);
    }
    String organization() {
        String id=UUID.randomUUID().toString(),event=UUID.randomUUID().toString();
        db.update("INSERT INTO person_foundation_event(id,aggregate_type,aggregate_id,aggregate_version,operation,after_json,operator_user_id,recorded_at,reason,verification_reference,source,receipt_id) VALUES(?,?,?,1,'SYNTHETIC_STORAGE_SENTINEL','{}',7001,UTC_TIMESTAMP(6),'fixture','synthetic','LOCAL_MANUAL',?)",event,"ORGANIZATION",id,key());
        db.update("INSERT INTO person_organization(id,name,description,status,revision,created_at,updated_at,last_change_id) VALUES(?,'Synthetic acceptance organization','fixture','ACTIVE',1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),?)",id,event);return id;
    }
    String key() {return UUID.randomUUID().toString();}
    String tag(String version) {return "\""+version+"\"";}
    @Test void migratedDefinitionsPassTheRealGlobalLoginCatalogVerifier() {
        var mapper=context.getBean(RbacMapper.class);
        var catalog=new RbacCatalog();
        catalog.verify(mapper.permissions(),mapper.roles(),mapper.members());
        assertEquals(79,mapper.permissions().size());
        assertEquals(28,mapper.roles().size());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM rbac_user_role_assignment",Integer.class));
        var transaction=new TransactionTemplate(context.getBean(DataSourceTransactionManager.class));
        transaction.executeWithoutResult(status->{
            // Reproduce an already-applied V67 with old definitions still at the V62 identity.
            db.update("UPDATE rbac_permission_definition SET catalog_digest='ea830aeddf262f2099d892e6f4cff290ffcc7ff29220abe6edad4f0a736b1424' WHERE code<>'points:acceptance-manage'");
            db.update("UPDATE rbac_role SET catalog_digest='ea830aeddf262f2099d892e6f4cff290ffcc7ff29220abe6edad4f0a736b1424' WHERE code<>'POINTS_ACCEPTANCE_STEWARD_V1'");
            assertThrows(ContractProblemException.class,()->catalog.verify(mapper.permissions(),mapper.roles(),mapper.members()));
            new ResourceDatabasePopulator(new ClassPathResource("db/migration/V68__align_points_acceptance_catalog_digest.sql"))
                    .execute(context.getBean(DataSource.class));
            catalog.verify(mapper.permissions(),mapper.roles(),mapper.members());
            status.setRollbackOnly();
        });
    }
    @Test void explicitQualificationReplayHistoryAndExistingAdmissionReaderAgree() {
        String org=organization(),key=key();var command=new Decision(true,"explicit synthetic approval");
        assertEquals("UNKNOWN",service.get(7001,org).state());
        var original=service.decide(7001,org,command,key,tag("unconfigured"));
        assertEquals(original,service.decide(7001,org,command,key,tag("unconfigured")));
        var policy=new PointsAcceptancePolicy(context.getBean(PointsMapper.class),null,null,null,null,null);
        assertTrue(policy.qualified(org));
        var stopped=service.decide(7001,org,new Decision(false,"pause acceptance"),key(),tag(original.version()));
        assertFalse(policy.qualified(org));assertNotEquals(original.version(),stopped.version());
        assertEquals(original,service.decide(7001,org,command,key,tag("unconfigured")));
        assertEquals("DISABLED",service.get(7001,org).state());
        assertEquals(2,service.history(7001,org,1,20).totalItems());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM point_ledger",Integer.class));
        assertEquals(409,assertThrows(ContractProblemException.class,()->service.decide(7001,org,new Decision(false,"changed intent"),key,tag("unconfigured"))).getStatus().value());
    }
    @Test void staleVersionAndInactiveOrganizationDoNotWrite() {
        String org=organization();var current=service.decide(7001,org,new Decision(true,"approve"),key(),tag("unconfigured"));
        assertEquals(412,assertThrows(ContractProblemException.class,()->service.decide(7001,org,new Decision(false,"stale"),key(),tag("unconfigured"))).getStatus().value());
        db.update("UPDATE person_organization SET status='INACTIVE' WHERE id=?",org);
        assertEquals(409,assertThrows(ContractProblemException.class,()->service.decide(7001,org,new Decision(true,"inactive"),key(),tag(current.version()))).getStatus().value());
        assertEquals("DISABLED",service.decide(7001,org,new Decision(false,"disable inactive"),key(),tag(current.version())).state());
        assertEquals(2,service.history(7001,org,1,20).totalItems());
    }
    @Test void finalAuthorizationFailureRollsBackEvidenceReceiptAndPointer() {
        String org=organization();var calls=new AtomicInteger();
        doAnswer(call->{if(calls.incrementAndGet()==2)throw new ContractProblemException(HttpStatus.FORBIDDEN,"/problems/forbidden","synthetic revoked session");return null;})
                .when(authentication).require(anyLong(),anyString(),anyString(),any());
        assertThrows(ContractProblemException.class,()->service.decide(7001,org,new Decision(true,"rollback"),key(),tag("unconfigured")));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM point_acceptance_evidence WHERE organization_id=?",Integer.class,org));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM point_acceptance_command WHERE organization_id=?",Integer.class,org));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM point_organization_acceptance WHERE organization_id=?",Integer.class,org));
    }
    @Test void oldCapabilityWithoutOwningDutyCannotReadOrReplay() {
        String org=organization(),intent=key();service.decide(7001,org,new Decision(true,"approved"),intent,tag("unconfigured"));
        when(reader.readCurrent(eq(7001L),any())).thenReturn(new RbacModels.Decision(7001,RbacModels.Source.ASSIGNMENT,"v","c",LocalDateTime.now(ZoneOffset.UTC),true,List.of(),List.of()));
        assertEquals(403,assertThrows(ContractProblemException.class,()->service.get(7001,org)).getStatus().value());
        assertEquals(403,assertThrows(ContractProblemException.class,()->service.decide(7001,org,new Decision(true,"approved"),intent,tag("unconfigured"))).getStatus().value());
        assertTrue(PlatformDutyPolicy.supports("POINTS_ACCEPTANCE_MANAGE"));
    }
    @Test void concurrentFirstDecisionsHaveOneWinnerAndOnePreconditionFailure() throws Exception {
        String org=organization();var pool=Executors.newFixedThreadPool(2);
        try {
            var first=pool.submit(()->attempt(org));var second=pool.submit(()->attempt(org));
            var statuses=List.of(first.get(15,TimeUnit.SECONDS),second.get(15,TimeUnit.SECONDS));
            assertTrue(statuses.contains(200));assertTrue(statuses.contains(412));
            assertEquals(1,service.history(7001,org,1,20).totalItems());
        } finally {pool.shutdownNow();}
    }
    int attempt(String org) {try{service.decide(7001,org,new Decision(true,"concurrent"),key(),tag("unconfigured"));return 200;}catch(ContractProblemException failure){return failure.getStatus().value();}}
    @Test void mvcReturnsVersionNoStoreAndRequiresPrecondition() throws Exception {
        String org=organization(),path="/v1/admin/points/accepting-organizations/"+org;
        mvc.perform(get(path)).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(header().string("ETag",tag("unconfigured")));
        mvc.perform(post(path+"/decisions").contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key",key()).content("{\"enabled\":true,\"reason\":\"checked\"}"))
                .andExpect(status().isPreconditionRequired());
        mvc.perform(post(path+"/decisions").contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key",key()).header("If-Match",tag("unconfigured")).content("{\"enabled\":true,\"reason\":\"checked\"}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
    }
    @Configuration
    @EnableTransactionManagement
    @MapperScan(basePackageClasses={PersonMapper.class,PointsMapper.class,RbacMapper.class})
    @Import({PointAcceptanceSettingsService.class,cn.jualn.miniapp.module.admin.operation.AdminAuditTestConfiguration.class})
    static class Config {
        @Bean DataSource dataSource() {return source(System.getProperty("managed.person.test.jdbcUrl"));}
        @Bean DataSourceTransactionManager transactionManager(DataSource source) {return new DataSourceTransactionManager(source);}
        @Bean ObjectMapper json() {return new ObjectMapper().findAndRegisterModules();}
        @Bean cn.jualn.miniapp.module.person.service.access.PersonBusinessScopes personScopes(PersonMapper mapper) {
            return new cn.jualn.miniapp.module.person.service.access.PersonBusinessScopes(
                mock(cn.jualn.miniapp.module.person.mapper.PersonBusinessMapper.class),mapper,
                mock(cn.jualn.miniapp.module.person.mapper.ManagedPersonMapper.class),
                mock(cn.jualn.miniapp.module.person.service.facts.PersonCatalog.class));
        }
        @Bean PersonAuthentication authentication() {return mock(PersonAuthentication.class);}
        @Bean RbacAuthorizationReader reader() {return mock(RbacAuthorizationReader.class);}
        @Bean UnifiedDutyReader duties() {return mock(UnifiedDutyReader.class);}
        @Bean TransactionalAuthorization accounts(RbacMapper mapper,RbacAuthorizationReader reader,PersonMapper personMapper) {return new TransactionalAuthorization(mapper,reader,new cn.jualn.miniapp.module.person.service.access.PersonAuthorityGate(personMapper));}
        @Bean SqlSessionFactory sqlSessionFactory(DataSource source) throws Exception {
            var factory=new MybatisSqlSessionFactoryBean();factory.setDataSource(source);
            var config=new MybatisConfiguration();config.setMapUnderscoreToCamelCase(true);config.setCacheEnabled(false);factory.setConfiguration(config);
            factory.setMapperLocations(new PathMatchingResourcePatternResolver().getResources("classpath*:mapper/*.xml"));return factory.getObject();
        }
    }
}


