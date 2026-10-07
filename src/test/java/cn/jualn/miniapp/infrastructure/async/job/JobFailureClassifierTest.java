package cn.jualn.miniapp.infrastructure.async.job;

import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.ResultCode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JobFailureClassifierTest {
    private final JobFailureClassifier classifier = new JobFailureClassifier();

    @Test
    void unknownOutcomeIsTerminalEvenWhenWrapped() {
        var result = classifier.classify(new RuntimeException(
                new UnknownOutcomeException("uncertain", new IllegalStateException("timeout"))));

        assertEquals(JobFailureClassifier.Kind.UNKNOWN, result.kind());
        assertEquals("remote", result.category());
    }

    @Test
    void businessFailureIsPermanentEvenWhenWrapped() {
        var result = classifier.classify(new RuntimeException(
                new BusinessException(ResultCode.INVALID_OPERATION)));

        assertEquals(JobFailureClassifier.Kind.PERMANENT, result.kind());
    }

    @Test
    void authoritativeRetryableRemoteFailureKeepsRemoteCategory() {
        var result = classifier.classify(new RetryableRemoteException("busy", null));

        assertEquals(JobFailureClassifier.Kind.RETRYABLE, result.kind());
        assertEquals("remote", result.category());
    }
}
