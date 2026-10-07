package cn.jualn.miniapp.module.activity.converter;

import cn.jualn.miniapp.module.activity.bo.ActivityListBO;
import cn.jualn.miniapp.module.search.converter.SearchConverter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ActivityLegacyRepresentationTest {
    @Test
    void listAndSearchKeepLegacyFieldsWithoutExposingNewInternalState() {
        var value = ActivityListBO.builder().id(1L).title("Activity").summary("Summary")
                .registrationMode(3).publishStatus(1).lifecycleStatus(0)
                .audienceDepartmentIds("[\"department-a\"]").participationState("EXTERNAL").build();
        var mapper = new ObjectMapper();
        var list = mapper.valueToTree(Mappers.getMapper(ActivityConverter.class).toVOList(List.of(value)));
        var search = mapper.valueToTree(Mappers.getMapper(SearchConverter.class).toActivityVOList(List.of(value)));
        assertEquals(list, search);
        assertEquals("Activity", list.get(0).get("title").asText());
        assertEquals(3, list.get(0).get("registrationMode").asInt());
        assertFalse(list.get(0).has("registrationLimit"));
        assertFalse(list.get(0).has("audienceDepartmentIds"));
        assertFalse(list.get(0).has("participationState"));
    }
}
