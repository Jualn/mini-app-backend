package cn.jualn.miniapp.module.report.service;

import cn.jualn.miniapp.module.report.mapper.*;
import cn.jualn.miniapp.module.report.entity.ReportResolution;
import cn.jualn.miniapp.module.notify.service.NotifyService;
import cn.jualn.miniapp.module.notify.bo.NotificationCenterBO.*;
import cn.jualn.miniapp.module.notify.enums.NotifyType;
import cn.jualn.miniapp.module.notify.payload.NotifyPayload;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.LocalDateTime;
import java.util.*;

/** Both report writers commit fixed resolution, covered reports and inbox facts together. */
@Service @RequiredArgsConstructor
public class ReportResolutionFacts {
    private final ReportResolutionMapper resolutions;
    private final ReportMapper reports;
    private final NotifyService notifications;
    private final ObjectMapper json;
    @Transactional(propagation=Propagation.MANDATORY)
    public void accept(String type,long target,List<Long> ids,String decision,String action,String outcome,long actor,String remark,LocalDateTime at) {
        resolutions.outcome(ids,outcome);
        var fact=new ReportResolution();fact.setTargetType(type);fact.setTargetId(target);fact.setDecision(decision);fact.setContentAction(action);fact.setOutcome(outcome);fact.setHandlerId(actor);fact.setRemark(remark);fact.setHandledAt(at);
        try {fact.setReportIds(json.writeValueAsString(ids.stream().map(String::valueOf).toList()));}catch(com.fasterxml.jackson.core.JsonProcessingException failure){throw new IllegalStateException(failure);}
        resolutions.insert(fact);
        for(long id:ids) {
            var report=reports.selectById(id);
            String title="举报处理结果",body="您的举报已有处理结果，请查看本人举报记录。";
            var targetLink=new Target("MY_REPORT_DETAIL",null,null,null,null,null,null,null,Long.toString(id));
            notifications.processNotificationPayload(NotifyPayload.builder().receiverId(report.getReporterId()).type(NotifyType.POST_REPORT_RESOLVED)
                    .sourceKey("report:"+id+":resolution:"+fact.getId()).title(title).content(body).inAppOnly(true)
                    .snapshot(new Snapshot(new Presentation(title,body,null,null,null),null,null,targetLink)).build());
        }
    }
    public List<cn.jualn.miniapp.module.report.bo.ReportResolutionBO> history(String type,long id) {
        return resolutions.history(type,id).stream().map(x -> {
            try {
                var ids=json.readValue(x.reportIds(),new com.fasterxml.jackson.core.type.TypeReference<List<String>>(){});
                return new cn.jualn.miniapp.module.report.bo.ReportResolutionBO(x.id(),x.handlerId(),x.handledAt().atOffset(java.time.ZoneOffset.ofHours(8)),x.decision(),x.contentAction(),x.outcome(),x.remark(),ids);
            } catch(com.fasterxml.jackson.core.JsonProcessingException failure) {throw new IllegalStateException(failure);}
        }).toList();
    }
}
