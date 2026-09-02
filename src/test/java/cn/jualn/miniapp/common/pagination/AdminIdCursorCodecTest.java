package cn.jualn.miniapp.common.pagination;

import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.ResultCode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AdminIdCursorCodecTest {

    @Test
    void shouldRoundTripOpaqueCursor() {
        String cursor = AdminIdCursorCodec.encode("latest", 123L);

        assertEquals(123L, AdminIdCursorCodec.decode(cursor, "latest"));
    }

    @Test
    void shouldReturnNullWhenCursorIsAbsent() {
        assertNull(AdminIdCursorCodec.decode(null, "latest"));
        assertNull(AdminIdCursorCodec.decode(" ", "latest"));
    }

    @Test
    void shouldRejectCursorFromDifferentSort() {
        String cursor = AdminIdCursorCodec.encode("latest", 123L);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> AdminIdCursorCodec.decode(cursor, "soonest"));

        assertEquals(ResultCode.BAD_REQUEST.getCode(), exception.getCode());
    }

    @Test
    void shouldRejectMalformedCursor() {
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> AdminIdCursorCodec.decode("not-a-cursor", "latest"));

        assertEquals(ResultCode.BAD_REQUEST.getCode(), exception.getCode());
    }
}
