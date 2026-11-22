package cn.jualn.miniapp.module.learningtask;

import cn.jualn.miniapp.config.JacksonConfig;
import cn.jualn.miniapp.module.authorization.model.UnifiedModels.Target;
import cn.jualn.miniapp.module.article.service.ArticleReferenceReadService;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Action;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Audience;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.OrganizationSelection;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Requirements;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Result;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Status;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Task;
import cn.jualn.miniapp.module.learningtask.controller.LearningTaskConfigurationController;
import cn.jualn.miniapp.module.learningtask.converter.LearningTaskDraftConverter;
import cn.jualn.miniapp.module.learningtask.entity.LearningAssignment;
import cn.jualn.miniapp.module.learningtask.mapper.LearningTaskLedgerMapper;
import cn.jualn.miniapp.module.learningtask.service.LearningTaskAuthorizationPolicy;
import cn.jualn.miniapp.module.learningtask.service.LearningTaskClosure;
import cn.jualn.miniapp.module.learningtask.service.LearningTaskConfigurationQueries;
import cn.jualn.miniapp.module.learningtask.service.LearningTaskScopePolicy;
import cn.jualn.miniapp.module.learningtask.service.LearningTaskService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

class LearningTaskConfigurationTest {
    private final LocalDateTime now = LocalDateTime.of(2026, 11, 22, 18, 0);
    private LearningAssignment assignment(String allocation, String progress, LocalDateTime until) {
        var row = new LearningAssignment();
        row.setAllocationStatus(allocation); row.setProgress(progress); row.setSupplementDeadline(until);
        return row;
    }
    @Test void closureUsesAllActiveRecordsAndExactDeadlineBoundaries() {
        assertEquals("NOT_PUBLISHED", LearningTaskClosure.evaluate("DRAFT", now, List.of(), now).reason());
        assertEquals(now.plusMinutes(1), LearningTaskClosure.evaluate("PUBLISHED", now.plusMinutes(1), List.of(), now).recheckAt());
        assertTrue(LearningTaskClosure.evaluate("PUBLISHED", now, List.of(), now).allowed());
        var rejected = assignment("ACTIVE", "REJECTED", now.plusHours(2));
        var later = assignment("ACTIVE", "REJECTED", now.plusHours(4));
        var pending = assignment("ACTIVE", "PENDING_REVIEW", null);
        assertEquals("PENDING_REVIEW", LearningTaskClosure.evaluate("PUBLISHED", now, List.of(rejected, pending), now).reason());
        assertEquals(now.plusHours(4), LearningTaskClosure.evaluate("PUBLISHED", now, List.of(rejected, later), now).recheckAt());
        assertTrue(LearningTaskClosure.evaluate("PUBLISHED", now, List.of(rejected, later), now.plusHours(4)).allowed());
        pending.setAllocationStatus("REVOKED");
        assertTrue(LearningTaskClosure.evaluate("PUBLISHED", now, List.of(pending), now).allowed());
    }
    @Test void actualSummaryControllerUsesAuthorizedMinimalLabelsAndApplicationJackson() throws Exception {
        var tasks = mock(LearningTaskService.class); var auth = mock(LearningTaskAuthorizationPolicy.class);
        var scopes = mock(LearningTaskScopePolicy.class); var ledger = mock(LearningTaskLedgerMapper.class);
        var articles = mock(ArticleReferenceReadService.class);
        when(auth.authorize(Action.READ, 11L, null)).thenReturn(9L);
        var audience = new Audience(List.of("p"), List.of(), List.of(), null,
                List.of(new OrganizationSelection("org", List.of("p"))));
        var task = new Task(11, "学习", "说明", 77L, Status.PUBLISHED,
                new Requirements(true, 0, 0, 0, List.of()), false, now, 1, audience, 1, 9, now, now, now, null, null, null, null,
                "AVAILABLE", new Target("ORGANIZATION", null, "org"));
        when(tasks.get(11)).thenReturn(new Result(task, null, null, null, 0, 0));
        when(scopes.configurationOrganizationName(9, 11, "org")).thenReturn("第一组织");
        when(articles.publicTitle(77)).thenReturn("学习材料");
        when(ledger.assignments(11)).thenReturn(List.of(assignment("ACTIVE", "PENDING_REVIEW", null)));
        var queries = new LearningTaskConfigurationQueries(tasks, auth, scopes, ledger, articles,
                Clock.fixed(Instant.parse("2026-11-22T10:00:00Z"), ZoneOffset.UTC));
        var json = new JacksonConfig().objectMapper(Jackson2ObjectMapperBuilder.json());
        var mvc = MockMvcBuilders.standaloneSetup(new LearningTaskConfigurationController(queries, new LearningTaskDraftConverter()))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(json)).build();
        var response = mvc.perform(get("/v1/admin/learning-tasks/11/configuration-summary"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store")).andReturn();
        var body = json.readTree(response.getResponse().getContentAsByteArray());
        assertEquals("第一组织", body.path("responsibleLabel").asText());
        assertEquals("学习材料", body.path("articleTitle").asText());
        assertTrue(body.path("closure").path("allowed").isBoolean());
        assertFalse(body.path("closure").path("allowed").asBoolean());
        assertFalse(body.path("closure").has("recheckAt"));
        assertTrue(body.path("organizations").get(0).path("organizationId").isTextual());
        verify(auth).authorize(Action.READ, 11L, null); verify(scopes).finish(9, false);
        var file = Path.of("target/learning-task-detail/http-responses.json"); Files.createDirectories(file.getParent());
        json.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), List.of(Map.of("schema", "LearningTaskConfigurationSummary", "status", 200, "body", body)));
        reset(articles);
        var hidden = new Task(11, "学习", null, 77L, Status.DRAFT, task.requirements(), false, now, 1, null, 1, 9, now, now,
                null, null, null, null, null, "UNAVAILABLE", new Target("PLATFORM", null, null));
        when(tasks.get(11)).thenReturn(new Result(hidden, null, null, null, 0, 0));
        var summary = queries.get(11);
        assertNull(summary.articleTitle()); assertEquals("平台", summary.responsibleLabel());
        verifyNoInteractions(articles); assertEquals("NOT_PUBLISHED", summary.closure().reason());
    }
}
