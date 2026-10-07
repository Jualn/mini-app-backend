package cn.jualn.miniapp.infrastructure.async.job;

/** External write may have succeeded although the caller did not obtain a trustworthy outcome. */
public class UnknownOutcomeException extends RuntimeException {
    public UnknownOutcomeException(String message, Throwable cause) {
        super(message, cause);
    }
}
