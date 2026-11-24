package cn.jualn.miniapp.module.activity.vo.admin;
public record ActivityStatisticsVO(long submittedCount, long cancelledCount, long registrationTotal,
        long subscriberCount, java.time.OffsetDateTime asOf) { }
