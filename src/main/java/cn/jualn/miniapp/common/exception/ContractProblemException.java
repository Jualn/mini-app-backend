package cn.jualn.miniapp.common.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;
import java.util.List;

/**
 * A stable RFC 9457 failure defined by the accepted cross-client contract.
 *
 * <p>This exception is intentionally separate from {@link BusinessException}:
 * internal result codes are not precise enough for protocol conditions such as
 * a missing or stale {@code If-Match} header.</p>
 */
@Getter
public final class ContractProblemException extends RuntimeException {
    private final HttpStatus status;
    private final String type;
    private final List<Violation> errors;
    private final String diagnostic;

    public ContractProblemException(HttpStatus status, String type, String detail) {
        this(status, type, detail, List.of(), null);
    }

    public ContractProblemException(HttpStatus status, String type, String detail, List<Violation> errors) {
        this(status, type, detail, errors, null);
    }

    private ContractProblemException(HttpStatus status, String type, String detail, List<Violation> errors,
            String diagnostic) {
        super(detail);
        this.status = status;
        this.type = type;
        this.errors = List.copyOf(errors);
        this.diagnostic = diagnostic;
    }

    public static ContractProblemException preconditionRequired() {
        return new ContractProblemException(HttpStatus.PRECONDITION_REQUIRED,
                "/problems/revision-required", "If-Match is required");
    }

    public static ContractProblemException preconditionFailed() {
        return preconditionFailed(null);
    }

    public static ContractProblemException preconditionFailed(String diagnostic) {
        return new ContractProblemException(HttpStatus.PRECONDITION_FAILED,
                "/problems/revision-mismatch", "The resource has changed; read it again before retrying",
                List.of(), diagnostic);
    }

    public static ContractProblemException conflict(String type, String detail) {
        return new ContractProblemException(HttpStatus.CONFLICT, "/problems/" + type, detail);
    }

    public static ContractProblemException conflict(String type, String detail, List<Violation> errors) {
        return new ContractProblemException(HttpStatus.CONFLICT, "/problems/" + type, detail, errors);
    }

    public static ContractProblemException validation(Violation error) {
        return new ContractProblemException(HttpStatus.BAD_REQUEST, "/problems/validation-error",
                "Request validation failed", List.of(error));
    }

    public static ContractProblemException notificationPreferencesUnavailable() {
        return new ContractProblemException(HttpStatus.SERVICE_UNAVAILABLE,
                "/problems/notification-preferences-unavailable",
                "通知偏好暂不可用，请稍后重新读取");
    }

    public record Violation(String in, String pointer, String code, String detail) {}
}
