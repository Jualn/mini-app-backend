package cn.jualn.miniapp.infrastructure.async.job;

public final class JobOwnershipLostException extends RuntimeException {
    public JobOwnershipLostException(String message) {
        super(message);
    }
}
