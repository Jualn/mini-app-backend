package cn.jualn.miniapp.module.exam.service;

import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.module.exam.bo.ExamPageBO;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PublicEventCursorCodecTest {
    @Test
    void roundTripsWhenFiltersMatch() {
        ExamPageBO query = query("robot", 2, 0);
        query.setCursor(PublicEventCursorCodec.encode(query, 42L));
        assertEquals(42L, PublicEventCursorCodec.decode(query));
    }

    @Test
    void rejectsCursorWhenAnyFilterChanges() {
        ExamPageBO first = query("robot", 2, 0);
        String cursor = PublicEventCursorCodec.encode(first, 42L);
        ExamPageBO changed = query("robot", 3, 0);
        changed.setCursor(cursor);
        assertThrows(ContractProblemException.class, () -> PublicEventCursorCodec.decode(changed));
    }

    @Test
    void rejectsTransparentLegacyIdCursor() {
        ExamPageBO query = query(null, null, null);
        query.setCursor("42");
        assertThrows(ContractProblemException.class, () -> PublicEventCursorCodec.decode(query));
    }

    private ExamPageBO query(String keyword, Integer type, Integer lifecycle) {
        return ExamPageBO.builder().keyword(keyword).eventType(type).lifecycleStatus(lifecycle).build();
    }
}
