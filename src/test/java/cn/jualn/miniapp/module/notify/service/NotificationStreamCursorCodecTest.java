package cn.jualn.miniapp.module.notify.service;

import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.module.notify.bo.NotificationCenterBO;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NotificationStreamCursorCodecTest {
    private final NotificationStreamCursorCodec codec = new NotificationStreamCursorCodec("notification-test-secret-32-chars-minimum");
    @Test void headIsIndependentOfCategoryAndSupportsEmptyStream() {
        assertEquals(0, codec.decodeHead(7, codec.head(7, 0)));
        assertEquals(123, codec.decodeHead(7, codec.head(7, 123)));
    }
    @Test void wrongUserPurposeAndFiltersCannotExpandReadBoundary() {
        String head = codec.head(7, 123);
        var query = new NotificationCenterBO.Query(null, 20, null, "ACTIVITY", null);
        String page = codec.page(7, 123, query);
        assertThrows(ContractProblemException.class, () -> codec.decodeHead(8, head));
        assertThrows(ContractProblemException.class, () -> codec.decodeHead(7, page));
        assertThrows(ContractProblemException.class, () -> codec.decodePage(7,
                new NotificationCenterBO.Query(head, 20, null, "ACTIVITY", null)));
        assertThrows(ContractProblemException.class, () -> codec.decodePage(7,
                new NotificationCenterBO.Query(page, 20, null, "SYSTEM", null)));
    }
    @Test void tamperAndRestartWithoutSharedKeyInvalidateTokens() {
        String token = codec.head(7, 12);
        assertThrows(ContractProblemException.class, () -> codec.decodeHead(7, token.substring(3)));
        assertThrows(ContractProblemException.class, () -> new NotificationStreamCursorCodec("").decodeHead(7, token));
    }
}
