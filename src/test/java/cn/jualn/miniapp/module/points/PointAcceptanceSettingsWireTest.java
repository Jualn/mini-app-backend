package cn.jualn.miniapp.module.points;

import cn.jualn.miniapp.config.JacksonConfig;
import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.module.authorization.service.TransactionalAuthorization;
import cn.jualn.miniapp.module.authorization.service.UnifiedDutyReader;
import cn.jualn.miniapp.module.person.service.access.PersonBusinessScopes;
import cn.jualn.miniapp.module.person.service.access.PersonAuthentication;
import cn.jualn.miniapp.module.points.mapper.PointAcceptanceSettingsMapper;
import cn.jualn.miniapp.module.points.service.PointAcceptanceSettingsService;
import cn.jualn.miniapp.module.points.bo.PointAcceptanceSettings.Decision;
import cn.jualn.miniapp.module.points.bo.PointAcceptanceSettings.Page;
import cn.jualn.miniapp.module.points.bo.PointAcceptanceSettings.Setting;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

class PointAcceptanceSettingsWireTest {
    private final com.fasterxml.jackson.databind.ObjectMapper json = new JacksonConfig().objectMapper(Jackson2ObjectMapperBuilder.json());

    @Test void productionMapperKeepsCountsNumericAndUnknownFactsExplicitlyNull() throws Exception {
        var setting = new Setting("o", "organization", "ACTIVE", "UNKNOWN", "unconfigured", null, null, null, null, null);
        var value = json.readTree(json.writeValueAsString(new Page(List.of(setting), 1, 20, 1L, "2026-10-27T00:00:00Z")));
        assertTrue(value.get("totalItems").isIntegralNumber());
        for (String field : List.of("enabled", "evidenceId", "reason", "recordedBy", "recordedAt")) {
            assertTrue(value.get("items").get(0).has(field));
            assertTrue(value.get("items").get(0).get(field).isNull());
        }
    }
    @Test void productionMapperRejectsCoercionAndUnknownRequestFields() throws Exception {
        assertEquals(new Decision(true, "checked"), json.readValue("{\"enabled\":true,\"reason\":\"checked\"}", Decision.class));
        for (String body : List.of("{\"enabled\":\"true\",\"reason\":\"r\"}", "{\"enabled\":1,\"reason\":\"r\"}",
                "{\"enabled\":true,\"reason\":42}", "{\"enabled\":true,\"reason\":\"r\",\"organizationType\":\"PARTY_BRANCH\"}")) {
            assertThrows(JsonProcessingException.class, () -> json.readValue(body, Decision.class));
        }
    }
    @Test void uncertainAuthorizationIsNotReportedAsMissingBusinessQualification() {
        var authentication = mock(PersonAuthentication.class);
        var source = new ContractProblemException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                "/problems/person-source-unavailable", "synthetic unavailable authorization source");
        doThrow(source).when(authentication).require(anyLong(), anyString(), anyString(), any());
        var service = new PointAcceptanceSettingsService(mock(PointAcceptanceSettingsMapper.class), mock(PersonBusinessScopes.class),
                authentication, mock(TransactionalAuthorization.class), mock(UnifiedDutyReader.class), json);
        var failure = assertThrows(ContractProblemException.class, () -> service.list(1, 1, 20));
        assertEquals("/problems/points-dependency-unavailable", failure.getType());
        assertEquals("AUTHORIZATION", failure.getMessage());
    }
}
