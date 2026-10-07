package cn.jualn.miniapp.infrastructure.async;

public final class AsyncMetricNames {
    public static final String OUTBOX_PUBLISH = "jualn.async.outbox.publish";
    public static final String OUTBOX_RETRY = "jualn.async.outbox.retry";
    public static final String OUTBOX_DEAD = "jualn.async.outbox.dead";
    public static final String OUTBOX_PENDING = "jualn.async.outbox.pending";
    public static final String OUTBOX_OLDEST_PENDING_AGE = "jualn.async.outbox.oldest.pending.age";
    public static final String JOB_EXECUTION = "jualn.async.job.execution";
    public static final String JOB_DURATION = "jualn.async.job.duration";
    public static final String JOB_RETRY = "jualn.async.job.retry";
    public static final String JOB_DEAD = "jualn.async.job.dead";
    public static final String JOB_RECOVERY = "jualn.async.job.recovery";
    public static final String JOB_READY = "jualn.async.job.ready";
    public static final String JOB_RUNNING = "jualn.async.job.running";
    public static final String JOB_RETRY_WAITING = "jualn.async.job.retry.waiting";
    public static final String JOB_DEAD_CURRENT = "jualn.async.job.dead.current";
    public static final String JOB_OLDEST_OVERDUE_AGE = "jualn.async.job.oldest.overdue.age";
    public static final String DATABASE_STATE_AVAILABLE = "jualn.async.database.state.available";
    public static final String STREAM_DELIVERY = "jualn.async.stream.delivery";
    public static final String STREAM_DURATION = "jualn.async.stream.duration";
    public static final String STREAM_RECLAIM = "jualn.async.stream.reclaim";
    public static final String STREAM_LENGTH = "jualn.async.stream.length";
    public static final String STREAM_LAG = "jualn.async.stream.lag";
    public static final String STREAM_PENDING = "jualn.async.stream.pending";
    public static final String STREAM_OLDEST_PENDING_AGE = "jualn.async.stream.oldest.pending.age";
    public static final String STREAM_CONSUMERS = "jualn.async.stream.consumers";
    public static final String STREAM_AVAILABLE = "jualn.async.stream.available";

    private AsyncMetricNames() {
    }
}
