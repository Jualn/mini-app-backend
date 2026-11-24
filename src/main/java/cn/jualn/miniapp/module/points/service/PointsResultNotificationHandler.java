package cn.jualn.miniapp.module.points.service;

import cn.jualn.miniapp.infrastructure.async.job.JobExecutionContext;
import cn.jualn.miniapp.infrastructure.async.job.JobHandler;

import org.springframework.stereotype.Component;

@Component
public class PointsResultNotificationHandler implements JobHandler<PointsResultNotifications.Result> {
    private final PointsResultNotifications results;
    public PointsResultNotificationHandler(PointsResultNotifications results) { this.results=results; }
    public String jobType() { return PointsResultNotifications.JOB; }
    public int schemaVersion() { return 2; }
    public Class<PointsResultNotifications.Result> payloadType() { return PointsResultNotifications.Result.class; }
    public void handle(PointsResultNotifications.Result result) {
        throw new IllegalStateException("Points result notification requires an owned job execution");
    }
    public void handle(PointsResultNotifications.Result result,JobExecutionContext context) {
        context.prepareBatch();results.deliver(result,context);
    }
}
