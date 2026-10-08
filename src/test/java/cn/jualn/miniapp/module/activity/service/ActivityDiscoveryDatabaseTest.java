package cn.jualn.miniapp.module.activity.service;

import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.common.exception.GlobalExceptionHandler;
import cn.jualn.miniapp.common.mapper.EnumConverter;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.module.activity.controller.CanonicalActivityController;
import cn.jualn.miniapp.module.activity.converter.ActivityConverter;
import cn.jualn.miniapp.module.activity.converter.ActivityResourceConverter;
import cn.jualn.miniapp.module.activity.mapper.ActivityMapper;
import cn.jualn.miniapp.module.activity.service.impl.ActivityServiceImpl;
import cn.jualn.miniapp.module.eventcontent.service.EventContactCodec;
import cn.jualn.miniapp.module.eventcontent.service.EventContentService;
import cn.jualn.miniapp.module.interact.service.InteractService;
import cn.jualn.miniapp.module.media.service.MediaService;
import cn.jualn.miniapp.module.notify.service.NotifyService;
import cn.jualn.miniapp.module.timeline.service.TimelineService;
import cn.jualn.miniapp.module.user.service.UserService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real MVC -> Service -> MapStruct -> MyBatis/MySQL. Other business services and auth are doubles.
 * Uses only a newly created synthetic table in a disposable schema; no migrations or existing data are touched. */
class ActivityDiscoveryDatabaseTest {
    private SingleConnectionDataSource source;
    private JdbcTemplate jdbc;
    private MockMvc mvc;
    private boolean createdTable;
    private final ObjectMapper json = Jackson2ObjectMapperBuilder.json().build();

    @BeforeEach
    void setup() throws Exception {
        String url = System.getProperty("event.test.jdbcUrl");
        org.junit.jupiter.api.Assumptions.assumeTrue(url != null, "Disposable MySQL required");
        if (!url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/activity_discovery_test")) {
            throw new IllegalArgumentException("Only the disposable activity_discovery_test schema is allowed");
        }
        source = new SingleConnectionDataSource(url, "root", "", true);
        jdbc = new JdbcTemplate(source);
        jdbc.execute("""
                CREATE TABLE activity (
                  id BIGINT PRIMARY KEY, title VARCHAR(200), summary TEXT, organizer VARCHAR(100),
                  audience_department_ids JSON, audience_scope INT, category INT, published_at DATETIME,
                  publish_status INT DEFAULT 1, lifecycle_status INT DEFAULT 0, deleted_at DATETIME,
                  contract_version BIGINT DEFAULT 1, form_version VARCHAR(100), form_schema JSON,
                  cancelled_at DATETIME, cancel_reason VARCHAR(100), audience_summary VARCHAR(200),
                  registration_mode INT DEFAULT 1, participant_mode INT DEFAULT 1, capacity INT,
                  capacity_unit INT, cover_attachment_id BIGINT, location VARCHAR(100)
                )
                """);
        createdTable = true;
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(configuration);
        factory.setMapperLocations(new ClassPathResource("mapper/ActivityMapper.xml"));
        var mapper = new SqlSessionTemplate(factory.getObject()).getMapper(ActivityMapper.class);
        var converter = Mappers.getMapper(ActivityConverter.class);
        ReflectionTestUtils.setField(converter, "enumConverter", new EnumConverter());
        var media = mock(MediaService.class);
        when(media.batchGetAttachments(any())).thenReturn(Map.of());
        var timelines = mock(TimelineService.class);
        when(timelines.listTimelinesByTargets(any(), any())).thenReturn(Map.of());
        var registrations = mock(ActivityRegistrationService.class);
        when(registrations.countSubmittedByActivityIds(any())).thenReturn(Map.of());
        var subscriptions = mock(ActivityEnrollmentService.class);
        var service = new ActivityServiceImpl(mock(EventContentService.class), mapper, converter,
                subscriptions, media, mock(UserService.class), timelines, mock(RedisService.class),
                mock(InteractService.class), mock(NotifyService.class), new EventContactCodec(json),
                new ActivityParticipationPolicy(new ActivityFormAvailability(false)), registrations, new ActivityFormAvailability(false));
        mvc = MockMvcBuilders.standaloneSetup(new CanonicalActivityController(service, subscriptions,
                new ActivityResourceConverter(json))).setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(json)).build();
        UserContext.setUserId(7L);
    }

    @AfterEach
    void cleanup() {
        UserContext.clear();
        if (source != null) {
            try {
                if (createdTable) jdbc.execute("DROP TABLE activity");
            } finally {
                source.destroy();
            }
        }
    }

    private void activity(long id, int mask, String departments, int category, int lifecycle, String summary) {
        jdbc.update("""
                INSERT INTO activity(id,title,summary,organizer,audience_scope,audience_department_ids,
                  category,lifecycle_status,published_at,audience_summary)
                VALUES(?,'activity',?,'robot organizer',?,?,?,?,'2026-10-08 12:00:00','specific year only')
                """, id, summary, mask, departments, category, lifecycle);
    }

    private JsonNode page(String audience, String department, String cursor, String keyword,
            String category, String lifecycle) throws Exception {
        var request = get("/v1/activities").param("pageSize", "20");
        if (audience != null) request.param("audienceFilter", audience);
        if (department != null) request.param("departmentId", department);
        if (cursor != null) request.param("cursor", cursor);
        if (keyword != null) request.param("q", keyword);
        if (category != null) request.param("category", category);
        if (lifecycle != null) request.param("lifecycleStatus", lifecycle);
        return json.readTree(mvc.perform(request).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private List<String> ids(JsonNode page) {
        List<String> result = new ArrayList<>();
        page.path("items").forEach(item -> result.add(item.path("activityId").asText()));
        return result;
    }

    @Test
    void explicitRangesAndLegacyFallbackPreserveStructuredAudience() throws Exception {
        activity(1, 1, null, 0, 0, "campus");
        activity(2, 0, "[\"information\",\"finance\"]", 0, 0, "multi department");
        activity(3, 0, "[\"science\"]", 0, 0, "science");
        activity(4, 2, null, 0, 0, "legacy numeric mask");
        activity(5, 0, "[\"unknown\"]", 0, 0, "unknown");
        activity(6, 1, "[\"information\"]", 0, 0, "structured IDs override legacy mask");
        activity(7, 0, "[\"1\"]", 0, 0, "legacy numeric ID");
        activity(8, 0, "[]", 0, 0, "empty departments means campus");
        assertEquals(List.of("8", "7", "6", "5", "4", "3", "2", "1"), ids(page("ALL", null, null, null, null, null)));
        assertEquals(List.of("8", "1"), ids(page("CAMPUS", null, null, null, null, null)));
        assertEquals(List.of("8", "1"), ids(page(null, null, null, null, null, null)));
        var information = page("DEPARTMENT", "information", null, null, null, null);
        assertEquals(List.of("8", "6", "2", "1"), ids(information));
        assertEquals("DEPARTMENTS", information.path("items").get(1).path("audienceScope").path("type").asText());
        assertEquals("specific year only", information.path("items").get(1).path("audienceSummary").asText());
        var all = page("ALL", null, null, null, null, null);
        assertEquals("1", all.path("items").get(1).path("audienceScope").path("departmentIds").get(0).asText());
        assertEquals(List.of("8", "3", "1"), ids(page("DEPARTMENT", "science", null, null, null, null)));
    }

    @Test
    void filtersEntireSetBeforePagingAndRejectsReusedCursor() throws Exception {
        for (int id = 1; id <= 45; id++) activity(id, 0, "[\"information\",\"finance\"]", 1, 0, "robot summary");
        // Newer non-matches occupy the first unfiltered pages.
        for (int id = 46; id <= 90; id++) activity(id, 0, "[\"science\"]", 1, 0, "robot summary");
        activity(91, 0, "[\"information\"]", 4, 0, "robot other category");
        activity(92, 0, "[\"information\"]", 1, 1, "robot ended");
        activity(93, 0, "[\"information\"]", 1, 0, "different summary");
        activity(94, 0, "[\"information\"]", 1, 0, "robot unpublished");
        jdbc.update("UPDATE activity SET publish_status=0 WHERE id=94");
        activity(95, 0, "[\"information\"]", 1, 0, "robot deleted");
        jdbc.update("UPDATE activity SET deleted_at=NOW() WHERE id=95");
        var first = page("DEPARTMENT", "information", null, "robot", "COMPETITION", "ACTIVE");
        assertEquals(20, ids(first).size());
        assertEquals("45", ids(first).get(0));
        assertEquals("26", ids(first).get(19));
        String cursor = first.path("nextCursor").asText();
        var second = page("DEPARTMENT", "information", cursor, "robot", "COMPETITION", "ACTIVE");
        assertEquals("25", ids(second).get(0));
        var third = page("DEPARTMENT", "information", second.path("nextCursor").asText(), "robot", "COMPETITION", "ACTIVE");
        assertEquals(List.of("5", "4", "3", "2", "1"), ids(third));
        assertTrue(third.path("nextCursor").isMissingNode() || third.path("nextCursor").isNull());
        assertTrue(ids(page("DEPARTMENT", "humanities", null, "robot", "COMPETITION", "ACTIVE")).isEmpty());
        assertEquals(List.of("92"), ids(page("DEPARTMENT", "information", null, "robot", "COMPETITION", "ENDED")));
        // Organizer text deliberately contains robot; only title/summary may match.
        assertFalse(ids(first).contains("93"));
        for (String audience : List.of("ALL", "CAMPUS", "DEPARTMENT")) {
            var request = get("/v1/activities").param("audienceFilter", audience).param("cursor", cursor)
                    .param("q", "robot").param("category", "COMPETITION").param("lifecycleStatus", "ACTIVE");
            if (audience.equals("DEPARTMENT")) request.param("departmentId", "finance");
            mvc.perform(request).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("/problems/invalid-cursor"));
        }
        UserContext.setUserId(8L);
        mvc.perform(get("/v1/activities").param("audienceFilter", "DEPARTMENT").param("departmentId", "information")
                .param("cursor", cursor).param("q", "robot").param("category", "COMPETITION").param("lifecycleStatus", "ACTIVE"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.type").value("/problems/invalid-cursor"));
    }
}
