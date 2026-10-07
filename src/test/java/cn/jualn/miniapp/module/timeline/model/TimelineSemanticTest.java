package cn.jualn.miniapp.module.timeline.model;

import cn.jualn.miniapp.common.enums.TargetType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimelineSemanticTest {

    @Test
    void separatesSubjectSpecificStartSemantics() {
        assertTrue(TimelineSemantic.ACTIVITY_START.supports(TargetType.ACTIVITY));
        assertFalse(TimelineSemantic.ACTIVITY_START.supports(TargetType.EXAM));
        assertTrue(TimelineSemantic.PUBLIC_EVENT_START.supports(TargetType.EXAM));
        assertFalse(TimelineSemantic.PUBLIC_EVENT_START.supports(TargetType.ACTIVITY));
        assertTrue(TimelineSemantic.REGISTRATION_END.supports(TargetType.ACTIVITY));
        assertTrue(TimelineSemantic.REGISTRATION_END.supports(TargetType.EXAM));
    }

    @Test
    void keepsUnknownStoredValuesUnknownInsteadOfGuessingFromDisplayText() {
        assertEquals("OTHER", TimelineSemantic.normalizeForRead("CUSTOM"));
        assertEquals("OTHER", TimelineSemantic.normalizeForRead(null));
        assertThrows(IllegalArgumentException.class,
                () -> TimelineSemantic.requireKnown("活动开始"));
    }
}
