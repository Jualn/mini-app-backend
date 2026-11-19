package cn.jualn.miniapp.module.home;

import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.common.exception.GlobalExceptionHandler;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.config.JacksonConfig;
import cn.jualn.miniapp.module.activity.mapper.ActivityMapper;
import cn.jualn.miniapp.module.activity.service.ActivityQueries;
import cn.jualn.miniapp.module.article.mapper.ArticleCategoryMapper;
import cn.jualn.miniapp.module.article.mapper.ArticleMapper;
import cn.jualn.miniapp.module.article.service.impl.ArticleServiceImpl;
import cn.jualn.miniapp.module.authorization.service.CurrentAuthorization;
import cn.jualn.miniapp.module.authorization.model.RbacModels;
import cn.jualn.miniapp.module.audit.service.AttachmentHistoryReader;
import cn.jualn.miniapp.module.content.service.AttachmentReferenceReader;
import cn.jualn.miniapp.module.authorization.service.RbacCatalog;
import cn.jualn.miniapp.module.content.mapper.AttachmentReferenceMapper;
import cn.jualn.miniapp.module.home.controller.HomeController;
import cn.jualn.miniapp.module.home.mapper.HomeBannerMapper;
import cn.jualn.miniapp.module.home.service.HomeBannerService;
import cn.jualn.miniapp.module.home.service.HomeSummaryQueries;
import cn.jualn.miniapp.module.home.service.HomeSummaryService;
import cn.jualn.miniapp.module.media.converter.MediaConverter;
import cn.jualn.miniapp.module.media.mapper.MediaAttachmentMapper;
import cn.jualn.miniapp.module.media.mapper.MediaUploadRecordMapper;
import cn.jualn.miniapp.module.media.service.AttachmentManagementService;
import cn.jualn.miniapp.module.media.service.AttachmentReadService;
import cn.jualn.miniapp.module.media.service.MediaAccess;
import cn.jualn.miniapp.module.media.service.MediaQueriesService;
import cn.jualn.miniapp.module.media.service.MediaUploadRecordService;
import cn.jualn.miniapp.module.media.service.PublicImageReuse;
import cn.jualn.miniapp.module.timeline.bo.TimelineFactBO;
import cn.jualn.miniapp.module.timeline.service.TimelineService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class HomeV1DatabaseTest {
    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class Transactions {}

    @Test
    void replaysAndUpgradesCatalogThenExercisesHomeHttpAndConcurrentWrites() throws Exception {
        String port = System.getProperty("home.test.port");
        assumeTrue(port != null, "Dedicated MySQL 8.4 instance required");
        assertTrue(port.matches("[0-9]+") && !port.equals("3306"));
        var fresh = source(port, "home_v1_fresh");
        var upgrade = source(port, "home_v1_upgrade");
        assertFresh(fresh);
        assertFresh(upgrade);
        var freshFlyway = Flyway.configure().dataSource(fresh).load();
        freshFlyway.migrate();
        freshFlyway.validate();
        Flyway.configure().dataSource(upgrade).target("93").load().migrate();
        var upgraded = new JdbcTemplate(upgrade);
        assertEquals("NOT_READY", upgraded.queryForObject(
                "SELECT readiness FROM rbac_permission_definition WHERE code='home-banner:manage'", String.class));
        upgraded.update("UPDATE rbac_permission_definition SET lifecycle='ENABLED', readiness='READY' WHERE code='article:read'");
        var upgradeFlyway = Flyway.configure().dataSource(upgrade).load();
        assertEquals(1, upgradeFlyway.migrate().migrationsExecuted);
        upgradeFlyway.validate();
        assertEquals("READY", upgraded.queryForObject(
                "SELECT readiness FROM rbac_permission_definition WHERE code='article:read'", String.class));
        assertEquals(1, upgraded.queryForObject("""
                SELECT COUNT(*) FROM rbac_permission_definition WHERE code='home-banner:manage'
                AND lifecycle='ENABLED' AND readiness='READY' AND grantable=1 AND unavailable_reason IS NULL
                """, Integer.class));
        assertEquals(1, upgraded.queryForObject("""
                SELECT COUNT(*) FROM rbac_role WHERE code='HOME_BANNER_MANAGER_V1'
                AND lifecycle='ENABLED' AND readiness='READY' AND grantable=1 AND unavailable_reason IS NULL
                """, Integer.class));
        assertEquals(new RbacCatalog().digest(), upgraded.queryForObject(
                "SELECT DISTINCT catalog_digest FROM rbac_role", String.class));

        var db = new JdbcTemplate(fresh);
        fixtures(db);
        var json = new JacksonConfig().objectMapper(Jackson2ObjectMapperBuilder.json());
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(HomeBannerMapper.class);
        configuration.addMapper(AttachmentReferenceMapper.class);
        configuration.addMapper(ArticleCategoryMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setConfiguration(configuration);
        factory.setDataSource(fresh);
        factory.setMapperLocations(new ClassPathResource("mapper/ActivityMapper.xml"),
                new ClassPathResource("mapper/ArticleMapper.xml"), new ClassPathResource("mapper/MediaAttachmentMapper.xml"));
        var sql = new SqlSessionTemplate(factory.getObject());
        var mapper = sql.getMapper(HomeBannerMapper.class);
        var articles = new ArticleServiceImpl(sql.getMapper(ArticleMapper.class), sql.getMapper(ArticleCategoryMapper.class),
                null, null, null, null, null, null, null, json, null);
        var timelines = mock(TimelineService.class);
        var now = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
        var ongoing = main(now.minusHours(1), now.plusHours(1), 2);
        var finished = main(now.minusHours(3), now.minusHours(1), 2);
        var future = main(now.plusHours(2), null, 2);
        var date = main(now.plusDays(1), null, 1);
        when(timelines.listTimelinesByTargets(any(), any())).thenReturn(Map.of(
                31L, List.of(ongoing), 32L, List.of(finished), 33L, List.of(future), 34L, List.of(date),
                35L, List.of(ongoing, future), 36L, List.of(future)));
        var activities = new ActivityQueries(null, sql.getMapper(ActivityMapper.class), null, null, null,
                null, timelines, null, null, null, null, null);
        var authorization = mock(CurrentAuthorization.class);
        var decision = mock(RbacModels.Decision.class);
        when(authorization.current(7)).thenReturn(decision);
        when(decision.canUseAdmin()).thenReturn(true);
        var attachments = sql.getMapper(MediaAttachmentMapper.class);
        var converter = org.mapstruct.factory.Mappers.getMapper(MediaConverter.class);
        var queries = new MediaQueriesService(converter, attachments, null, null);
        var read = mock(AttachmentReadService.class);
        when(read.getPublicAttachment(any())).thenThrow(new BusinessException(ResultCode.MEDIA_ATTACHMENT_NOT_FOUND));
        var uploads = mock(MediaUploadRecordService.class);
        when(uploads.registeredPurpose(201L, 7)).thenReturn(TargetType.ACTIVITY);
        var images = new PublicImageReuse(attachments, queries, mock(MediaAccess.class), read, uploads, null, null);
        var references = new AttachmentReferenceReader(sql.getMapper(AttachmentReferenceMapper.class));
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(Transactions.class);
            context.registerBean(DataSourceTransactionManager.class, () -> new DataSourceTransactionManager(fresh));
            context.registerBean(AttachmentManagementService.class, () -> new AttachmentManagementService(
                    authorization, attachments, mock(MediaUploadRecordMapper.class), converter, references, mock(AttachmentHistoryReader.class)));
            context.registerBean(cn.jualn.miniapp.module.article.service.ArticleOrganizationPolicy.class,
                    () -> mock(cn.jualn.miniapp.module.article.service.ArticleOrganizationPolicy.class));
            context.registerBean(HomeBannerService.class, () -> new HomeBannerService(mapper, authorization, articles, activities, images));
            context.registerBean(HomeSummaryQueries.class, () -> new HomeSummaryQueries(mapper, articles, activities, images));
            context.registerBean(HomeSummaryService.class);
            context.refresh();
            var service = context.getBean(HomeBannerService.class);
            var mvc = MockMvcBuilders.standaloneSetup(new HomeController(service, context.getBean(HomeSummaryService.class)))
                    .setControllerAdvice(new GlobalExceptionHandler())
                    .setMessageConverters(new MappingJackson2HttpMessageConverter(json)).build();
            UserContext.setUserId(7L);
            try {
                var initial = mvc.perform(get(HomeController.BANNERS)).andExpect(status().isOk())
                        .andExpect(header().string("Cache-Control", "no-store")).andExpect(jsonPath("$.items").isEmpty())
                        .andReturn().getResponse().getHeader("ETag");
                var article = item("ARTICLE", "21", true, ",\"coverAttachmentId\":\"201\"");
                var department = item("ACTIVITY", "36", true, "");
                var body = items(department, article);
                mvc.perform(put(HomeController.BANNERS).contentType("application/json").content(body))
                        .andExpect(status().is(428));
                mvc.perform(put(HomeController.BANNERS).header("If-Match", "*").contentType("application/json").content(body))
                        .andExpect(status().isBadRequest());
                mvc.perform(put(HomeController.BANNERS).header("If-Match", initial).contentType("application/json")
                                .content(items(article, article)))
                        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("DUPLICATE_KEY"));
                mvc.perform(put(HomeController.BANNERS).header("If-Match", initial).contentType("application/json")
                                .content(items(item("ACTIVITY", "999", false, ""))))
                        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("INVALID_REFERENCE"));
                var saved = mvc.perform(put(HomeController.BANNERS).header("If-Match", initial)
                                .contentType("application/json").content(body))
                        .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].target.targetId").value("36"))
                        .andReturn().getResponse().getHeader("ETag");
                assertNotEquals(initial, saved);
                assertTrue(references.inUse(201));
                var attachmentManagement = context.getBean(AttachmentManagementService.class);
                assertEquals("/problems/attachment-in-use", assertThrows(ContractProblemException.class,
                        () -> attachmentManagement.delete(7, 201)).getType());
                mvc.perform(put(HomeController.BANNERS).header("If-Match", saved).contentType("application/json")
                                .content(items(item("ARTICLE", "21", true, ",\"coverAttachmentId\":\"202\""))))
                        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("INVALID_REFERENCE"));
                mvc.perform(get(HomeController.OPTIONS).param("targetType", "ACTIVITY").param("q", " campus ")
                                .param("page", "2").param("pageSize", "1"))
                        .andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").isNumber()).andExpect(jsonPath("$.totalItems").value(5))
                        .andExpect(jsonPath("$.items.length()").value(1));
                mvc.perform(get(HomeController.OPTIONS).param("targetType", "ACTIVITY").param("targetId", "36"))
                        .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].target.targetId").value("36"));
                mvc.perform(get(HomeController.OPTIONS).param("targetType", "ARTICLE").param("q", "latest"))
                        .andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(2))
                        .andExpect(jsonPath("$.items[0].target.targetId").value("23"));

                mvc.perform(get(HomeController.SUMMARY)).andExpect(status().isOk())
                        .andExpect(jsonPath("$.evaluatedAt").value(org.hamcrest.Matchers.endsWith("+08:00")))
                        .andExpect(jsonPath("$.banners.items.length()").value(1))
                        .andExpect(jsonPath("$.banners.items[0].cover.url").value("https://example.org/cover.jpg"))
                        .andExpect(jsonPath("$.latestArticles.items[0].id").value("23"))
                        .andExpect(jsonPath("$.latestArticles.items[1].id").value("21"))
                        .andExpect(jsonPath("$.latestArticles.items.length()").value(2))
                        .andExpect(jsonPath("$.upcomingActivities.items[0].activityId").value("31"))
                        .andExpect(jsonPath("$.upcomingActivities.items[0].schedule.kind").value("EXACT_RANGE"))
                        .andExpect(jsonPath("$.upcomingActivities.items[1].activityId").value("33"));

                db.update("UPDATE article SET publish_status='UNPUBLISHED' WHERE id=21");
                mvc.perform(get(HomeController.BANNERS)).andExpect(header().string("ETag", saved))
                        .andExpect(jsonPath("$.items.length()").value(2));
                mvc.perform(get(HomeController.SUMMARY)).andExpect(jsonPath("$.banners.status").value("EMPTY"));
                var pool = Executors.newFixedThreadPool(2);
                try {
                    var first = pool.submit(() -> replace(service, saved, json.readTree(items(article))));
                    var second = pool.submit(() -> replace(service, saved, json.readTree(items(department))));
                    assertEquals(List.of(200, 412), List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS))
                            .stream().sorted().toList());
                } finally {
                    pool.shutdownNow();
                }
                var current = service.get(7);
                service.replace(7, current.etag(), json.readTree(items()));
                assertFalse(references.inUse(201));
                attachmentManagement.delete(7, 201);
                assertTrue(db.queryForObject("SELECT deleted FROM media_attachment WHERE id=201", Boolean.class));
                verify(authorization, atLeastOnce()).requirePublishedPlatformDuty(
                        7, "HOME_BANNER_MANAGE", "HOME", "home-banner:manage");
            } finally {
                UserContext.clear();
            }
        }
    }

    private static DriverManagerDataSource source(String port, String schema) {
        var source = new DriverManagerDataSource("jdbc:mysql://127.0.0.1:" + port + "/" + schema
                + "?sslMode=DISABLED&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai", "root", "");
        String uuid = System.getProperty("home.test.serverUuid");
        assertNotNull(uuid, "Verify the dedicated server UUID before use");
        assertEquals(uuid, new JdbcTemplate(source).queryForObject("SELECT @@server_uuid", String.class));
        return source;
    }

    private static void assertFresh(DriverManagerDataSource source) {
        assertEquals(0, new JdbcTemplate(source).queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()", Integer.class));
    }

    private static void fixtures(JdbcTemplate db) {
        db.update("INSERT INTO article_publisher (id,display_name,updated_by,updated_at) VALUES (71,'Publisher',7,NOW())");
        db.update("""
                INSERT INTO article (id,title,summary,body,plain_text,category_id,publisher_id,created_by,updated_by,
                    created_at,updated_at,publish_status,published_at,last_published_at)
                SELECT 21,'latest republished','summary',JSON_OBJECT(),'text',id,71,7,7,NOW(),NOW(),
                    'PUBLISHED','2026-10-10','2026-11-11' FROM article_category WHERE fixed_scene_code='PARTY_NEWS'
                UNION ALL
                SELECT 22,'other scene','summary',JSON_OBJECT(),'text',id,71,7,7,NOW(),NOW(),
                    'PUBLISHED','2026-11-10','2026-11-10' FROM article_category WHERE fixed_scene_code='E_SAFETY'
                UNION ALL
                SELECT 23,'latest party article','summary',JSON_OBJECT(),'text',id,71,7,7,NOW(),NOW(),
                    'PUBLISHED','2026-10-11','2026-10-11' FROM article_category WHERE fixed_scene_code='PARTY_NEWS'
                """);
        db.update("""
                INSERT INTO media_attachment (id,type,kind,registered,registered_by,object_key,url,retention,expires_at)
                VALUES (201,1,'IMAGE',1,7,'attachment-resource/7/cover','https://example.org/cover.jpg','FORMAL',NULL),
                       (202,1,'IMAGE',1,7,'attachment-resource/7/candidate','https://example.org/candidate.jpg',
                           'CANDIDATE',NOW() + INTERVAL 1 HOUR)
                """);
        for (int id = 31; id <= 36; id++) {
            db.update("INSERT INTO activity (id,user_id,title,publish_status,published_at,audience_department_ids) VALUES (?,7,?,1,NOW(),?)",
                    id, id == 36 ? "department" : "campus " + id, id == 36 ? "[\"information\"]" : "[]");
        }
    }

    private static TimelineFactBO main(LocalDateTime start, LocalDateTime end, int precision) {
        return TimelineFactBO.builder().nodeType("ACTIVITY_START").startTime(start).startPrecision(precision)
                .endTime(end).endPrecision(end == null ? null : precision).build();
    }

    private static String item(String type, String id, boolean enabled, String extra) {
        return """
                {"target":{"targetType":"%s","targetId":"%s"},"enabled":%s%s}
                """.formatted(type, id, enabled, extra);
    }

    private static String items(String... items) {
        return "{\"items\":[" + String.join(",", items) + "]}";
    }

    private static int replace(HomeBannerService service, String etag, com.fasterxml.jackson.databind.JsonNode body) {
        try {
            service.replace(7, etag, body);
            return 200;
        } catch (cn.jualn.miniapp.common.exception.ContractProblemException e) {
            return e.getStatus().value();
        }
    }
}
