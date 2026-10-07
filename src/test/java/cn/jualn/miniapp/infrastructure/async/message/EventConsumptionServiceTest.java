package cn.jualn.miniapp.infrastructure.async.message;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EventConsumptionServiceTest {
    @Test
    void duplicateMessageIsAlreadyConsumed() {
        EventConsumptionMapper mapper = mock(EventConsumptionMapper.class);
        when(mapper.insert("audit-completed-v1", "m1"))
                .thenReturn(1)
                .thenThrow(new DuplicateKeyException("duplicate"));
        EventConsumptionService service = new EventConsumptionService(mapper);

        assertTrue(service.beginOnce("audit-completed-v1", "m1"));
        assertFalse(service.beginOnce("audit-completed-v1", "m1"));
    }
}
