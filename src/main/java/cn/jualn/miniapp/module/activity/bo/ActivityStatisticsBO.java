package cn.jualn.miniapp.module.activity.bo;
public record ActivityStatisticsBO(long submittedCount, long cancelledCount, long registrationTotal,
        long subscriberCount, java.time.LocalDateTime asOf) { }
