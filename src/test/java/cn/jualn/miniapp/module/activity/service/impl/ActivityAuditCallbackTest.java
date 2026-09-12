package cn.jualn.miniapp.module.activity.service.impl;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ActivityAuditCallbackTest {
    @Test void retiredCallbacksHaveNoPersistenceDependencies() {
        var callback = new ActivityAuditCallback();
        assertDoesNotThrow(() -> callback.onPass(7L));
        assertDoesNotThrow(() -> callback.onReject(7L, "historical risk"));
    }
}
