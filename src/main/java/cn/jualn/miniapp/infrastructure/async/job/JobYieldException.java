package cn.jualn.miniapp.infrastructure.async.job;

public final class JobYieldException extends RuntimeException {
    public JobYieldException(String message) {
        super(message);
    }
}
