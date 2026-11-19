package cn.jualn.miniapp.common.exception;

/**
 * Existing HTTP rate-limit semantics shared by entry adapters.
 */
public class RateLimitedException extends RuntimeException {
    private final long retryAfter;

    public RateLimitedException(String message, long retryAfter) {
        super(message);
        this.retryAfter = retryAfter;
    }

    public long retryAfter() {
        return retryAfter;
    }
}
