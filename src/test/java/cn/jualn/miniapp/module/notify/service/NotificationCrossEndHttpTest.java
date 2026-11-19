package cn.jualn.miniapp.module.notify.service;

import cn.dev33.satoken.SaManager;
import cn.jualn.miniapp.module.notify.enums.NotifyType;
import cn.jualn.miniapp.config.*;
import cn.jualn.miniapp.module.notify.bo.NotificationCenterBO;
import cn.jualn.miniapp.module.notify.payload.NotifyPayload;
import cn.jualn.miniapp.module.user.service.UserService;
import cn.jualn.miniapp.module.user.service.impl.UserServiceImpl;
import cn.jualn.miniapp.third.wx.client.WxClient;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mapstruct.factory.Mappers;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real production HTTP/auth/read/write chain. Only upstream WeChat and unrelated domains are doubles. */
@EnabledIfSystemProperty(named="event.test.jdbcUrl",matches="jdbc:mysql://127[.]0[.]0[.]1:33991/notification_contract_foundation")
class NotificationCrossEndHttpTest {
    static final long USER=920000001L, OTHER=920000002L;
    static final Path EVIDENCE=Path.of("target/notification-cross-integration");
    final Map<String,NotifyPayload> payloads=new ConcurrentHashMap<>();
    final List<Object> controls=Collections.synchronizedList(new ArrayList<>());
    ServletWebServerApplicationContext context;
    JdbcTemplate db;
    ObjectMapper json;

    @Test void realAuthenticatedSessionsDriveTheCurrentMiniProgramConsumer() throws Exception {
        Files.createDirectories(EVIDENCE);
        ExecutorService executor=Executors.newFixedThreadPool(3);
        HttpServer control=null;
        try {
            context=(ServletWebServerApplicationContext)new SpringApplicationBuilder(Config.class).run(
                "--spring.config.location=optional:classpath:/notification-cross-no-config.yaml",
                "--server.address=127.0.0.1","--server.port=0","--spring.flyway.enabled=false",
                "--spring.main.banner-mode=off","--spring.jmx.enabled=false",
                "--spring.data.redis.host=127.0.0.1","--spring.data.redis.port=6391",
                "--sa-token.token-name=Authorization","--sa-token.token-prefix=Bearer",
                "--sa-token.is-share=false","--sa-token.is-concurrent=true","--sa-token.is-log=false",
                "--sa-token.jwt-secret-key=notification-cross-disposable-test-signing-key-only",
                "--logging.level.root=WARN");
            db=new JdbcTemplate(context.getBean(DataSource.class));json=context.getBean(ObjectMapper.class);
            assertTrue(SaManager.getSaTokenDao().getClass().getName().contains("Redis"));
            db.update("INSERT INTO user_profile(id,openid,nickname,role,status) VALUES(920000001,'notification-cross-user','Cross user',1,1),(920000002,'notification-cross-other','Other user',1,1) ON DUPLICATE KEY UPDATE status=1");
            // These are isolated synthetic analogues, never copies of the protected business rows.
            for(int i=0;i<20;i++) db.update("INSERT INTO notification(id,user_id,type,title,content,inbox_generation,is_read,read_at,created_at) VALUES(?,?,7,'protected synthetic history','fixture','LEGACY',1,DATE_SUB(NOW(),INTERVAL 199 DAY),DATE_SUB(NOW(),INTERVAL 200 DAY))",930000001L+i,USER);
            String protectedBefore=protectedFingerprint();
            control=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            String nonce=UUID.randomUUID().toString();
            control.setExecutor(executor);
            control.createContext("/fixture",exchange->{
                try {
                    if(!nonce.equals(exchange.getRequestHeaders().getFirst("X-Fixture-Key"))) {exchange.sendResponseHeaders(403,-1);return;}
                    JsonNode command=json.readTree(exchange.getRequestBody());
                    Object result=fixture(command);
                    controls.add(Map.of("action",command.path("action").asText(),"result",result));
                    byte[] body=json.writeValueAsBytes(result);exchange.getResponseHeaders().set("Content-Type","application/json");
                    exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);
                } catch(Exception failure) {
                    byte[] body=json.writeValueAsBytes(Map.of("error",failure.getClass().getSimpleName()));
                    exchange.sendResponseHeaders(500,body.length);exchange.getResponseBody().write(body);
                } finally {exchange.close();}
            });
            control.start();
            var process=new ProcessBuilder("node","scripts/test-notification-backend-http.mjs");
            process.directory(Path.of("../mini-program").toFile());
            process.environment().put("NOTIFICATION_HTTP_BASE","http://127.0.0.1:"+context.getWebServer().getPort());
            process.environment().put("NOTIFICATION_FIXTURE_BASE","http://127.0.0.1:"+control.getAddress().getPort());
            process.environment().put("NOTIFICATION_FIXTURE_KEY",nonce);
            process.environment().put("NOTIFICATION_EVIDENCE",EVIDENCE.toAbsolutePath().toString());
            process.redirectErrorStream(true);process.redirectOutput(EVIDENCE.resolve("mini-program-http.log").toFile());
            Process child=process.start();
            try {
                assertTrue(child.waitFor(150,TimeUnit.SECONDS),"bounded cross-end consumer run");
                assertEquals(0,child.exitValue(),Files.readString(EVIDENCE.resolve("mini-program-http.log")));
            } finally {if(child.isAlive()) child.destroyForcibly();}
            context.getBean(NotificationLifecycleService.class).cleanup();
            assertEquals(protectedBefore,protectedFingerprint(),"all 20 unclassified analogues survive maintenance unchanged");
            var evidence=new LinkedHashMap<String,Object>();
            evidence.put("database",db.queryForMap("SELECT @@server_uuid AS serverUuid,DATABASE() AS schemaName,VERSION() AS version"));
            evidence.put("flyway",db.queryForMap("SELECT COUNT(*) AS applied,MAX(CAST(version AS UNSIGNED)) AS maxVersion,SUM(NOT success) AS failures FROM flyway_schema_history"));
            evidence.put("saTokenDao",SaManager.getSaTokenDao().getClass().getName());
            evidence.put("protectedSyntheticRows",20);evidence.put("protectedBefore",protectedBefore);evidence.put("protectedAfter",protectedFingerprint());
            evidence.put("controls",controls);evidence.put("remaining",stats());
            Files.writeString(EVIDENCE.resolve("database-evidence.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(evidence));
        } finally {
            if(control!=null)control.stop(0);executor.shutdownNow();
            if(context!=null)context.close();
        }
    }

    String protectedFingerprint() {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(
                json.writeValueAsBytes(db.queryForList("SELECT * FROM notification WHERE id BETWEEN 930000001 AND 930000020 ORDER BY id"))));
        } catch(Exception failure) {throw new IllegalStateException(failure);}
    }
    Object fixture(JsonNode request) {
        String action=request.path("action").asText();
        return switch(action) {
            case "create" -> {
                String key=request.path("key").asText();
                var payload=payloads.computeIfAbsent(key,ignored->{
                    var builder=NotifyPayload.builder().receiverId(request.path("other").asBoolean()?OTHER:USER)
                        .sourceKey("cross-end:"+key).type(NotifyType.valueOf(request.path("type").asText("SYSTEM")))
                        .title("fixture "+key).content("frozen fixture").inAppOnly(true);
                    if(request.path("postTarget").asBoolean())builder.snapshot(new NotificationCenterBO.Snapshot(
                        new NotificationCenterBO.Presentation("post target fixture","frozen",null,null,List.of()),null,null,
                        new NotificationCenterBO.Target("POST_DETAIL","777",null,null,null)));
                    if(request.path("unknownTarget").asBoolean())builder.snapshot(new NotificationCenterBO.Snapshot(
                        new NotificationCenterBO.Presentation("unknown target fixture","frozen",null,null,List.of()),null,null,
                        new NotificationCenterBO.Target("UNRECOGNIZED_TARGET",null,null,null,null)));
                    return builder.build();
                });
                if(request.path("legacy").asBoolean()) {
                    context.getBean(NotifyService.class).processNotificationPayload(payload);
                    yield Map.of("id",db.queryForObject("SELECT id FROM notification WHERE source_key=?",Long.class,payload.getSourceKey()),"outcome","LEGACY_ACCEPTED");
                }
                var result=context.getBean(NotificationCreator.class).create(payload);
                yield Map.of("id",result.notificationId(),"outcome",result.outcome().name());
            }
            case "age" -> {
                long id=request.path("id").asLong();
                assertTrue(id<930000001L || id>930000020L,"protected fixture may not be aged");
                var date=NotificationObservation.now().minusDays(90).plusSeconds(request.path("secondsUntilExpiry").asLong(-1));
                assertEquals(1,db.update("UPDATE notification SET created_at=? WHERE id=? AND source_key LIKE 'cross-end:%'",date,id));
                db.update("UPDATE notification_source_receipt SET original_created_at=? WHERE notification_id=?",date,id);
                yield Map.of("id",id,"createdAt",date.toString(),"expiresAt",date.plusDays(90).toString());
            }
            case "cache" -> {
                context.getBean(cn.jualn.miniapp.infrastructure.cache.RedisService.class).setCacheProjection(
                    cn.jualn.miniapp.common.constant.RedisKeyConstant.userUnreadCount(USER),999L,Duration.ofMinutes(2));
                yield Map.of("staleUnreadProjection",999);
            }
            case "cleanup" -> {context.getBean(NotificationLifecycleService.class).cleanup();yield stats();}
            case "stats" -> stats();
            default -> throw new IllegalArgumentException("Unknown isolated fixture action");
        };
    }
    Map<String,Object> stats() {
        return Map.of("rows",db.queryForList("SELECT id,type,is_read,read_at,deleted_at,created_at,inbox_seq,inbox_generation FROM notification WHERE source_key LIKE 'cross-end:%' ORDER BY id"),
            "protectedCount",db.queryForObject("SELECT COUNT(*) FROM notification WHERE id BETWEEN 930000001 AND 930000020",Integer.class),
            "receipts",db.queryForObject("SELECT COUNT(*) FROM notification_source_receipt",Integer.class));
    }

    @Configuration(proxyBeanMethods=false) @EnableAutoConfiguration
    @MapperScan({"cn.jualn.miniapp.module.notify.mapper","cn.jualn.miniapp.module.user.mapper"})
    @Import({SaTokenConfig.class,RedisConfig.class,WebMvcConfig.class,
        cn.jualn.miniapp.web.ContextInterceptor.class,
        cn.jualn.miniapp.module.auth.controller.AuthController.class,
        cn.jualn.miniapp.module.auth.service.impl.AuthServiceImpl.class})
    static class Config extends NotificationFoundationDatabaseTest.Config {
        // Replace the foundation's synthetic identity interceptor with the production MVC chain above.
        @Override public void addInterceptors(InterceptorRegistry registry) { }
        @Override @Bean SqlSessionFactory sqlSessionFactory(DataSource source)throws Exception {
            var factory=new MybatisSqlSessionFactoryBean();factory.setDataSource(source);
            var global=new com.baomidou.mybatisplus.core.config.GlobalConfig();global.setMetaObjectHandler(new MybatisPlusConfig().metaObjectHandler());
            global.setDbConfig(new com.baomidou.mybatisplus.core.config.GlobalConfig.DbConfig());
            global.getDbConfig().setLogicNotDeleteValue("NULL");global.getDbConfig().setLogicDeleteValue("NOW()");factory.setGlobalConfig(global);
            factory.setMapperLocations(new PathMatchingResourcePatternResolver().getResources("classpath*:mapper/*.xml"));return factory.getObject();
        }
        @Bean cn.jualn.miniapp.module.admin.auth.service.AdminTokenService adminTokenService(){return mock(cn.jualn.miniapp.module.admin.auth.service.AdminTokenService.class);}
        @Bean cn.jualn.miniapp.module.user.service.UserAccountService accounts(
                cn.jualn.miniapp.module.user.mapper.UserProfileMapper profiles,
                cn.jualn.miniapp.infrastructure.cache.RedisService redis) {
            return new cn.jualn.miniapp.module.user.service.UserAccountService(profiles, redis,
                mock(cn.jualn.miniapp.module.authorization.service.RbacGovernanceService.class),
                mock(cn.jualn.miniapp.module.authorization.service.CurrentAuthorization.class),
                mock(cn.jualn.miniapp.module.admin.operation.service.AdminAuditJournal.class));
        }
        @Bean cn.jualn.miniapp.module.user.service.UserWechatIdentityService identities(
                cn.jualn.miniapp.module.user.mapper.UserProfileMapper profiles,
                org.springframework.context.ApplicationEventPublisher events) {
            return new cn.jualn.miniapp.module.user.service.UserWechatIdentityService(profiles,
                Mappers.getMapper(cn.jualn.miniapp.module.user.converter.UserConverter.class), events,
                mock(cn.jualn.miniapp.module.authorization.service.AuthorizationAuthorityService.class));
        }
        @Bean UserService userService(cn.jualn.miniapp.module.user.mapper.UserProfileMapper profiles,
                cn.jualn.miniapp.module.user.mapper.UserAgreementMapper agreements,
                cn.jualn.miniapp.infrastructure.cache.RedisService redis,org.springframework.context.ApplicationEventPublisher events,
                org.springframework.transaction.PlatformTransactionManager transactions,
                cn.jualn.miniapp.module.user.service.UserAccountService accounts,
                cn.jualn.miniapp.module.user.service.UserWechatIdentityService identities) {
            return new UserServiceImpl(profiles,
                accounts, identities,
                redis,
                Mappers.getMapper(cn.jualn.miniapp.module.user.converter.UserConverter.class),
                mock(cn.jualn.miniapp.module.media.TestMediaService.class),
                mock(cn.jualn.miniapp.module.audit.service.ProfileSafetyCheckService.class),
                transactions,
                mock(cn.jualn.miniapp.module.authorization.service.CurrentAuthorization.class));
        }
        @Bean WxClient wx() {
            var wx=mock(WxClient.class,RETURNS_DEEP_STUBS);
            when(wx.getMiniSession("cross-session-a").getOpenid()).thenReturn("notification-cross-user");
            when(wx.getMiniSession("cross-session-b").getOpenid()).thenReturn("notification-cross-user");
            when(wx.getMiniSession("cross-other-user").getOpenid()).thenReturn("notification-cross-other");
            return wx;
        }
    }
}
