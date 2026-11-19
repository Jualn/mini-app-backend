package cn.jualn.miniapp.module.notify.converter;

import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.module.notify.bo.*;
import cn.jualn.miniapp.module.notify.dto.request.*;
import cn.jualn.miniapp.module.notify.enums.NotifyType;
import cn.jualn.miniapp.module.notify.vo.*;
import org.springframework.stereotype.Component;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;

/** HTTP compatibility representation and presence/unknown-field validation. No persistence or policy reads. */
@Component
public class NotificationPersonalConverter {
    public NotificationInboxBO.Query query(CanonicalNotificationQuery value) {
        return new NotificationInboxBO.Query(value.getCursor(),value.getPageSize(),value.getCategory(),value.getIsRead());
    }
    public NotificationPreferencesBO.Patch patch(UpdateNotificationPreferencesRequest value) {
        if(value==null)throw invalid("/","REQUIRED","请求体不能为空");
        unknown(value.unknownProperties(),"");
        if(value.getChanges()==null)throw invalid("/changes","REQUIRED","changes 不能为空");
        var changes=new ArrayList<NotificationPreferencesBO.Change>();
        for(int i=0;i<value.getChanges().size();i++) {
            var change=value.getChanges().get(i);
            if(change==null)throw invalid("/changes/"+i,"REQUIRED","category 与 channel 不能为空");
            unknown(change.unknownProperties(),"/changes/"+i);
            if(!change.enabledPresent())throw invalid("/changes/"+i+"/enabled","REQUIRED","缺少 enabled");
            changes.add(new NotificationPreferencesBO.Change(change.getCategory(),change.getChannel(),change.getEnabled()));
        }
        return new NotificationPreferencesBO.Patch(java.util.List.copyOf(changes));
    }
    public NotificationPreferencesVO preferences(NotificationPreferencesBO value) {
        return new NotificationPreferencesVO(value.defaultVersion(),value.items().stream()
                .map(v->new NotificationPreferencesVO.Item(v.category(),v.channel(),v.enabled(),v.source())).toList());
    }
    public NotificationChannelCapabilitiesVO capabilities(NotificationCapabilitiesBO value) {
        return new NotificationChannelCapabilitiesVO(value.evaluatedAt().toString(),value.items().stream()
                .map(v->new NotificationChannelCapabilitiesVO.Item(v.category(),v.notificationType(),v.channel(),v.available(),v.permission(),v.unavailableReasons())).toList());
    }
    public CanonicalNotificationPageVO page(NotificationInboxBO.Page value) {
        return new CanonicalNotificationPageVO(value.items().stream().map(this::item).toList(),value.nextCursor());
    }
    private CanonicalNotificationPageVO.Item item(NotificationInboxBO.Item value) {
        var type=NotifyType.fromCode(value.type());
        var target=value.target()==null?null:new CanonicalNotificationPageVO.Target(
                "EXAM".equals(value.target().type())?"PUBLIC_EVENT":value.target().type(),value.target().resourceId().toString());
        return new CanonicalNotificationPageVO.Item(value.notificationId().toString(),
                type==null?"UNKNOWN_"+value.type():type.legacyRepresentation().name(),value.category(),value.title(),value.content(),target,value.isRead(),
                value.createdAt().atZone(ZoneId.of("Asia/Shanghai")).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
    }
    private void unknown(java.util.Map<String,Object> fields,String prefix) {
        if(!fields.isEmpty())throw invalid(prefix+"/"+fields.keySet().iterator().next(),"UNKNOWN_PROPERTY","不支持的字段");
    }
    private ContractProblemException invalid(String pointer,String code,String detail) {
        return ContractProblemException.validation(new ContractProblemException.Violation("body",pointer,code,detail));
    }
}
