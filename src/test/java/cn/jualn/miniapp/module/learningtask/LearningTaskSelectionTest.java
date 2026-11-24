package cn.jualn.miniapp.module.learningtask;

import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO;
import cn.jualn.miniapp.module.learningtask.converter.LearningTaskDraftConverter;
import cn.jualn.miniapp.module.learningtask.dto.admin.LearningTaskDraftJson;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import cn.jualn.miniapp.module.learningtask.controller.LearningTaskOptionsController;
import cn.jualn.miniapp.module.learningtask.service.LearningTaskRecipientOptions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.MediaType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LearningTaskSelectionTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void longExclusionsUseJsonArraysAndUnknownFieldsAreRejected() throws Exception {
        var service = mock(LearningTaskRecipientOptions.class);
        when(service.list(any(),any(),any(),any(),any(),anyList(),anyList(),any(),anyInt(),anyBoolean(),anyList(),anyBoolean()))
                .thenReturn(new LearningTaskRecipientOptions.Page(List.of(), null));
        var mvc = MockMvcBuilders.standaloneSetup(new LearningTaskOptionsController(service)).build();
        mvc.perform(post("/v1/admin/learning-task-recipient-options").contentType(MediaType.APPLICATION_JSON).content("""
                {"targetType":"PLATFORM","type":"PERSON","selectableOnly":true,"excludePersonIds":["p1","p2"]}
                """)).andExpect(status().isOk());
        verify(service).list(null,"PLATFORM",null,"PERSON",null,List.of(),List.of(),null,20,true,List.of("p1","p2"),false);
        mvc.perform(post("/v1/admin/learning-task-recipient-options").contentType(MediaType.APPLICATION_JSON).content("""
                {"targetType":"PLATFORM","type":"PERSON","arbitraryPermission":true}
                """)).andExpect(status().isBadRequest());
    }

    @Test void organizationGroupsRoundTripAsExplicitPeopleWithoutDynamicFilters() throws Exception {
        var request = LearningTaskDraftJson.audience(json.readTree("""
                {"persons":{"personIds":["p1","p2"],"organizationSelections":[{"organizationId":"org-a","personIds":["p1"]}]}}
                """));
        var value = new LearningTaskDraftConverter().audience(request);
        assertEquals(List.of("p1", "p2"), value.personIds());
        assertEquals(List.of(), value.filters());
        assertEquals(List.of("p1"), value.organizationSelections().get(0).personIds());
        var stored = json.readValue(json.writeValueAsString(value), LearningTaskBO.Audience.class);
        assertEquals(value, stored);
        var legacy = json.readValue("{\"personIds\":[\"p1\"],\"accountUserIds\":[],\"filters\":[]}", LearningTaskBO.Audience.class);
        assertEquals(List.of(), legacy.organizationSelections());
        assertFalse(json.writeValueAsString(legacy).contains("organizationSelections"));
    }

    @Test void groupingCannotAddPeopleOrRepeatAnOrganization() throws Exception {
        var outside = json.readTree("""
                {"persons":{"personIds":["p1"],"organizationSelections":[{"organizationId":"org-a","personIds":["outside"]}]}}
                """);
        assertThrows(RuntimeException.class, () -> LearningTaskDraftJson.audience(outside));
        var duplicate = json.readTree("""
                {"persons":{"personIds":["p1"],"organizationSelections":[{"organizationId":"org-a","personIds":["p1"]},{"organizationId":"org-a","personIds":["p1"]}]}}
                """);
        assertThrows(RuntimeException.class, () -> LearningTaskDraftJson.audience(duplicate));
    }
}
