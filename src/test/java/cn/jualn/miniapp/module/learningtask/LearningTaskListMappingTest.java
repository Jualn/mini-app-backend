package cn.jualn.miniapp.module.learningtask;

import cn.jualn.miniapp.config.JacksonConfig;
import cn.jualn.miniapp.module.authorization.model.UnifiedModels.Target;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.AdminPage;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Audience;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.OrganizationSelection;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Requirements;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Status;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Task;
import cn.jualn.miniapp.module.learningtask.controller.LearningTaskDraftController;
import cn.jualn.miniapp.module.learningtask.converter.LearningTaskDraftConverter;
import cn.jualn.miniapp.module.learningtask.service.LearningTaskProviders;
import cn.jualn.miniapp.module.learningtask.service.LearningTaskService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LearningTaskListMappingTest {
    @Test void actualListJsonPreservesStringIdsNumericCountsAndAbsentOptionalAudienceFields() throws Exception {
        var json = new JacksonConfig().objectMapper(Jackson2ObjectMapperBuilder.json());
        var service = mock(LearningTaskService.class);
        var grouped = new Audience(List.of("person-alpha"), List.of(), List.of(), null,
                List.of(new OrganizationSelection("org-alpha", List.of("person-alpha"))));
        var explicit = new Audience(List.of("person-beta"), List.of(), List.of(), null);
        var accounts = new Audience(List.of(), List.of("9007199254740993001"), List.of(), null);
        when(service.listAdmin(any())).thenReturn(new AdminPage(
                List.of(task(grouped), task(explicit), task(accounts), task(null)), 1, 20, 4));
        var mvc = MockMvcBuilders.standaloneSetup(new LearningTaskDraftController(service,
                        mock(LearningTaskProviders.class), new LearningTaskDraftConverter()))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(json)).build();
        var result = mvc.perform(get("/v1/admin/learning-tasks").param("page", "1").param("pageSize", "20"))
                .andExpect(status().isOk()).andReturn();
        var body = json.readTree(result.getResponse().getContentAsByteArray());
        var output = Path.of("target/learning-task-list/http-responses.json");
        Files.createDirectories(output.getParent());
        json.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), List.of(Map.of(
                "schema", "LearningTaskAdminPage", "status", 200,
                "producer", "Actual list controller, converter and application Jackson; service and authorization are explicit doubles",
                "body", body)));
        assertTrue(body.path("page").isIntegralNumber());
        assertTrue(body.path("pageSize").isIntegralNumber());
        assertTrue(body.path("totalItems").isIntegralNumber());
        for (var item : body.path("items")) {
            assertTrue(item.path("taskId").isTextual());
            assertEquals("9007199254740993001", item.path("taskId").textValue());
            assertTrue(item.path("createdBy").isTextual());
            assertTrue(item.path("deadlineRevision").isIntegralNumber());
        }
        var people = body.path("items").path(0).path("audience").path("persons");
        assertEquals("person-alpha", people.path("personIds").path(0).textValue());
        assertFalse(people.has("filters"));
        assertFalse(people.has("authorizedScopeId"));
        var plain = body.path("items").path(1).path("audience").path("persons");
        assertFalse(plain.has("organizationSelections"));
        assertFalse(body.path("items").path(2).path("audience").has("persons"));
        assertFalse(body.path("items").path(3).has("audience"));
    }

    private Task task(Audience audience) {
        var at = LocalDateTime.of(2026, 11, 22, 18, 0);
        return new Task(9007199254740993001L, "列表任务", "说明", null, Status.DRAFT,
                new Requirements(true, 0, 0, 0, List.of()), false, at.plusDays(1), 1,
                audience, 1, 9007199254740993001L, at, at, null, null, null, null, null, null,
                new Target("ORGANIZATION", null, "org-alpha"));
    }
}
