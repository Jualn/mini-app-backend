package cn.jualn.miniapp.module.notify.async;

import cn.jualn.miniapp.infrastructure.async.job.JobFailureClassifier;
import cn.jualn.miniapp.infrastructure.async.job.UnknownOutcomeException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LegacyNotificationDeliveryJobHandlerTest {
    @Test
    void persistedLegacyPayloadRemainsReadableButCannotAutomaticallyReplaySend() throws Exception {
        var handler = new LegacyNotificationDeliveryJobHandler();
        assertEquals(1, handler.schemaVersion());
        var payload = new ObjectMapper().readValue(
                "{\"receiverId\":7,\"noticeType\":\"REPLY\",\"data\":{\"title\":\"test\"}}",
                handler.payloadType());
        var error = assertThrows(UnknownOutcomeException.class, () -> handler.handle(payload));
        assertEquals(JobFailureClassifier.Kind.UNKNOWN, new JobFailureClassifier().classify(error).kind());
    }
}
