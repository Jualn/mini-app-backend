package cn.jualn.miniapp.infrastructure.async.job;

import cn.jualn.miniapp.common.exception.BusinessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientRequestException;

@Component
public class JobFailureClassifier {
    public Failure classify(Throwable error) {
        if (contains(error, UnknownOutcomeException.class)) {
            return new Failure(Kind.UNKNOWN, "remote");
        }
        if (contains(error, JobYieldException.class)) {
            return new Failure(Kind.RETRYABLE, "internal");
        }
        if (contains(error, JobOwnershipLostException.class)) {
            return new Failure(Kind.RETRYABLE, "conflict");
        }
        if (contains(error, RetryableRemoteException.class)) {
            return new Failure(Kind.RETRYABLE, "remote");
        }
        if (contains(error, JobHandlerRegistry.UnsupportedJobException.class)
                || contains(error, IllegalArgumentException.class)
                || contains(error, BusinessException.class)) {
            return new Failure(Kind.PERMANENT, "validation");
        }
        if (contains(error, TransientDataAccessException.class)) {
            return new Failure(Kind.RETRYABLE, "database");
        }
        if (contains(error, WebClientRequestException.class)) {
            return new Failure(Kind.RETRYABLE, "remote");
        }
        return new Failure(Kind.RETRYABLE, "internal");
    }

    private boolean contains(Throwable error, Class<? extends Throwable> type) {
        Throwable current = error;
        while (current != null) {
            if (type.isInstance(current)) return true;
            if (current.getCause() == current) return false;
            current = current.getCause();
        }
        return false;
    }

    public enum Kind { RETRYABLE, PERMANENT, UNKNOWN }
    public record Failure(Kind kind, String category) {}
}
