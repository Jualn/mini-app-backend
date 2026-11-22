package cn.jualn.miniapp.common.web;

import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.config.JacksonConfig;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminQueryBoundaryTest {
    @Test void batchInputsAreBoundedAndNeverSilentlyDeduplicated() {
        for (var ids : List.of(List.<String>of(), List.of("1", "1"), List.of(""),
                IntStream.range(0, 101).mapToObj(Integer::toString).toList(), List.of("x".repeat(129)))) {
            assertThrows(ContractProblemException.class, () -> AdminQuery.validate(ids, "userId", 100));
        }
        var request = new MockHttpServletRequest();
        request.addParameter("userId", "2", "opaque-id", "1");
        assertEquals(List.of("2", "opaque-id", "1"), AdminQuery.values(request, "userId", 100));
        request.addParameter("userId[]", "3");
        assertThrows(ContractProblemException.class, () -> AdminQuery.values(request, "userId", 100));
    }

    @Test void unnamedReadyAndHiddenResourcesHaveDifferentWireShapes() throws Exception {
        var mapper = new JacksonConfig().objectMapper(new Jackson2ObjectMapperBuilder());
        var ready = mapper.valueToTree(AdminNamesVO.user(new NameReadBO("1", "READY", null, null)));
        var hidden = mapper.valueToTree(AdminNamesVO.user(new NameReadBO("2", "UNAVAILABLE", null, null)));
        assertTrue(ready.has("name"));
        assertTrue(ready.path("name").isNull());
        assertFalse(hidden.has("name"));
        assertFalse(hidden.has("problem"));
    }

}
