package cn.jualn.miniapp.common.web;

import cn.jualn.miniapp.common.exception.ContractProblemException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StrongEtagTest {
    @Test
    void requiresTheExactStrongCurrentTag() {
        assertEquals("\"activity-7-v3\"", StrongEtag.of("activity", 7, 3));
        assertDoesNotThrow(() -> StrongEtag.require("\"activity-7-v3\"", "activity", 7, 3));
        assertEquals(428, assertThrows(ContractProblemException.class,
                () -> StrongEtag.require(null, "activity", 7, 3)).getStatus().value());
        assertEquals(412, assertThrows(ContractProblemException.class,
                () -> StrongEtag.require("*", "activity", 7, 3)).getStatus().value());
        ContractProblemException mismatch = assertThrows(ContractProblemException.class,
                () -> StrongEtag.require("\"activity-7-v2\"", "activity", 7, 3));
        assertEquals(412, mismatch.getStatus().value());
        assertTrue(mismatch.getDiagnostic().contains("expected=\"activity-7-v3\""));
        assertTrue(mismatch.getDiagnostic().contains("supplied=\"activity-7-v2\""));
    }
}
