package cn.jualn.miniapp.infrastructure.async;

public record OutboxStateSnapshot(long pending, long oldestPendingAgeSeconds) {
}
