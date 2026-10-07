package cn.jualn.miniapp.infrastructure.async.job;

/** A provider authoritative response confirms the write was not accepted and may be retried. */
public class RetryableRemoteException extends RuntimeException {
    public RetryableRemoteException(String message, Throwable cause) {
        super(message, cause);
    }
}
