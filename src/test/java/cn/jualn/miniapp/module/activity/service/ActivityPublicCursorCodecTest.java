package cn.jualn.miniapp.module.activity.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.module.activity.bo.ActivityPageBO;
import org.junit.jupiter.api.Test;

class ActivityPublicCursorCodecTest {
    @Test
    void roundTripsWhenFiltersMatch() {
        ActivityPageBO query = query("robot", 1, 0);
        query.setCursor(ActivityPublicCursorCodec.encode(query, 42L));
        assertEquals(42L, ActivityPublicCursorCodec.decode(query));
    }

    @Test
    void rejectsCursorWhenAnyFilterChanges() {
        ActivityPageBO first = query("robot", 1, 0);
        String cursor = ActivityPublicCursorCodec.encode(first, 42L);
        ActivityPageBO changed = query("robot", 2, 0);
        changed.setCursor(cursor);
        assertThrows(ContractProblemException.class, () -> ActivityPublicCursorCodec.decode(changed));
    }

    @Test
    void rejectsTransparentLegacyIdCursor() {
        ActivityPageBO query = query(null, null, null);
        query.setCursor("42");
        assertThrows(ContractProblemException.class, () -> ActivityPublicCursorCodec.decode(query));
    }

    private ActivityPageBO query(String keyword, Integer category, Integer lifecycle) {
        return ActivityPageBO.builder().keyword(keyword).category(category).lifecycleStatus(lifecycle)
                .campusAudienceOnly(true).build();
    }
}
