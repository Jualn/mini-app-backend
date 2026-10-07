package cn.jualn.miniapp.infrastructure.async;

public record JobStateSnapshot(long ready, long running, long retryWaiting, long dead,
                               long oldestOverdueAgeSeconds) {
}
