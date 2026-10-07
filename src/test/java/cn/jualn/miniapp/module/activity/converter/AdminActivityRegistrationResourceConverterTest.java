package cn.jualn.miniapp.module.activity.converter;

import cn.jualn.miniapp.module.activity.bo.ActivityRegistrationBO;
import cn.jualn.miniapp.module.activity.bo.ActivityRegistrationContractPageBO;
import cn.jualn.miniapp.module.activity.dto.admin.AdminActivityRegistrationQuery;
import cn.jualn.miniapp.module.activity.service.ActivityFormPolicy;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AdminActivityRegistrationResourceConverterTest {
    @Test
    void exposesRawContractAnswersWithoutPersonalCapabilities() {
        var row = new ActivityRegistrationBO();
        row.setId(3L); row.setActivityId(7L); row.setUserId(9L); row.setStatus(2);
        row.setFormVersion("form-v1"); row.setFormData(ActivityFormPolicy.parse("{\"track\":[\"ai\"]}"));
        row.setSubmittedAt(LocalDateTime.of(2026, 9, 14, 10, 0));
        row.setUpdatedAt(LocalDateTime.of(2026, 9, 14, 11, 0));
        row.setCancelledAt(LocalDateTime.of(2026, 9, 14, 11, 0));
        var converter = new AdminActivityRegistrationResourceConverter();

        var result = converter.page(new ActivityRegistrationContractPageBO(List.of(row), 1, 20, 1));

        assertEquals("CANCELLED", result.items().get(0).status());
        assertEquals("ai", result.items().get(0).answers().get(0).value().get(0).asText());
        assertEquals("2026-09-14T10:00+08:00", result.items().get(0).submittedAt().toString());
    }

    @Test
    void statusFilterMapsOnlyCanonicalStates() {
        var converter = new AdminActivityRegistrationResourceConverter();
        var query = new AdminActivityRegistrationQuery();
        query.setStatus("SUBMITTED");
        assertEquals(1, converter.status(query));
        query.setStatus("CANCELLED");
        assertEquals(2, converter.status(query));
    }
}
