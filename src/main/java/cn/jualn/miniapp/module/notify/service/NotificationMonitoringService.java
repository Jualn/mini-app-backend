package cn.jualn.miniapp.module.notify.service;
import cn.jualn.miniapp.common.security.AdminStpUtil;
import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.module.authorization.service.CurrentAuthorization;
import cn.jualn.miniapp.module.notify.service.capability.NotificationCatalogQueries;
import cn.jualn.miniapp.module.notify.bo.NotificationCatalogBO;
import cn.jualn.miniapp.module.notify.mapper.NotificationMonitoringMapper;
import cn.jualn.miniapp.module.notify.model.NotificationCategory;
import cn.jualn.miniapp.module.notify.model.NotificationChannel;
import cn.jualn.miniapp.module.notify.enums.NotifyType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
@Service @RequiredArgsConstructor
public class NotificationMonitoringService {
    private final CurrentAuthorization authorization;
    private final NotificationCatalogQueries catalog;
    private final NotificationMonitoringMapper mapper;
    private void authorize(long user){authorization.requirePlatform(user,"notice:read","NOTIFICATION","NOTICE_MONITOR","notice:read");}
    public NotificationCatalogBO.Status status(long user){
        try {authorize(user);return catalog.status();}
        catch(org.springframework.dao.DataAccessException unavailable){throw NotificationCatalogQueries.unavailable();}
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public NotificationCatalogBO.Summary summary(long user,OffsetDateTime from,OffsetDateTime until,NotificationChannel channel,NotificationCategory category){
        try {
            authorize(user);
            if(from==null||until==null||!from.isBefore(until)||Duration.between(from.toInstant(),until.toInstant()).compareTo(Duration.ofDays(31))>0)
                throw ContractProblemException.validation(new ContractProblemException.Violation("query","from","RANGE","时间范围必须大于零且不超过31天"));
            if(from.toInstant().isBefore(Instant.now().minus(Duration.ofDays(90))))throw NotificationCatalogQueries.unavailable();
            var start=LocalDateTime.ofInstant(from.toInstant(),ZoneId.of("Asia/Shanghai"));
            var end=LocalDateTime.ofInstant(until.toInstant(),ZoneId.of("Asia/Shanghai"));
            String selected=channel==null?null:channel.name();
            if(mapper.unknown(start,end,selected)>0)throw NotificationCatalogQueries.unavailable();
            var totals=new LinkedHashMap<java.util.List<String>,Long>();
            for(var row:mapper.summary(start,end,selected,category==null?null:category.name())){
                var type=NotifyType.fromCode(((Number)row.get("notificationType")).intValue());
                if(NotificationCategory.of(type)==null)throw NotificationCatalogQueries.unavailable();
                String state=String.valueOf(row.get("status"));String outcome="DELIVERED".equals(state)?"ACCEPTED":state;
                String reason=reason(state,(String)row.get("errorCode"),(String)row.get("resultCategory"));
                totals.merge(List.of(type.name(),String.valueOf(row.get("channel")),outcome,reason),((Number)row.get("total")).longValue(),Math::addExact);
            }
            var items=totals.entrySet().stream().map(e->new NotificationCatalogBO.SummaryItem(e.getKey().get(0),e.getKey().get(1),e.getKey().get(2),e.getKey().get(3),e.getValue())).toList();
            return new NotificationCatalogBO.Summary(NotificationCatalogQueries.now(),from,until,items);
        }catch(org.springframework.dao.DataAccessException unavailable){throw NotificationCatalogQueries.unavailable();}
    }
    private String reason(String state,String code,String category){
        if("UNKNOWN".equals(state))return "PROVIDER_OUTCOME_UNKNOWN";
        if("FAILED".equals(state)&&"remote".equals(category))return "PROVIDER_REJECTED";
        if(code==null)return "";
        if(code.equals(Integer.toString(cn.jualn.miniapp.common.result.ResultCode.WX_OPENID_NOT_BOUND.getCode())))return "IDENTITY_REQUIRED";
        return switch(code){
            case "channel_disabled" -> "PREFERENCE_DISABLED";
            case "channel_unavailable","template_unavailable","legacy_template_unfrozen" -> "TEMPLATE_UNAVAILABLE";
            case "business_ineligible" -> "BUSINESS_INELIGIBLE";
            case "notification_expired" -> "EXPIRED";
            default -> "";
        };
    }
}
