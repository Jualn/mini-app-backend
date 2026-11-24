package cn.jualn.miniapp.module.publicevent.service;

import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.module.publicevent.bo.PublicEventPageBO;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PublicEventCursorCodecTest {
    @Test
    void roundTripsWhenFiltersMatch() {
        PublicEventPageBO query = query("robot", 2, 0);
        query.setCursor(PublicEventCursorCodec.encode(query, 42L));
        assertEquals(42L, PublicEventCursorCodec.decode(query));
    }

    @Test
    void rejectsCursorWhenAnyFilterChanges() {
        PublicEventPageBO first = query("robot", 2, 0);
        String cursor = PublicEventCursorCodec.encode(first, 42L);
        PublicEventPageBO changed = query("robot", 3, 0);
        changed.setCursor(cursor);
        assertThrows(ContractProblemException.class, () -> PublicEventCursorCodec.decode(changed));
    }

    @Test
    void rejectsTransparentLegacyIdCursor() {
        PublicEventPageBO query = query(null, null, null);
        query.setCursor("42");
        assertThrows(ContractProblemException.class, () -> PublicEventCursorCodec.decode(query));
    }

    private PublicEventPageBO query(String keyword, Integer type, Integer lifecycle) {
        return PublicEventPageBO.builder().keyword(keyword).eventType(type).lifecycleStatus(lifecycle).build();
    }
}
