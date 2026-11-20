package cn.jualn.miniapp.module.wx.notice;

import cn.jualn.miniapp.module.notify.enums.NotifyType;
import cn.jualn.miniapp.module.notify.model.NotificationCategory;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Explicit safe frozen fields only. Private material, review notes and binding evidence are never template sources. */
public final class WxNoticeFieldCatalog {
    private WxNoticeFieldCatalog() {}
    public static boolean knownType(String type){try{return NotificationCategory.of(NotifyType.valueOf(type))!=null;}catch(RuntimeException unknown){return false;}}
    public static boolean allows(String type,String source){
        if(!knownType(type)||source==null)return false;
        if(Set.of("noticeTitle","noticeSummary","eventTime","notificationId","noticeHint").contains(source))return true;
        var value=NotifyType.valueOf(type);
        if(Set.of("reviewResult","reviewTime").contains(source) && Set.of(NotifyType.AUDIT_RESULT,NotifyType.LEARNING_TASK_REVIEW_APPROVED,NotifyType.LEARNING_TASK_REVIEW_REJECTED,NotifyType.POINT_APPLICATION_APPROVED,NotifyType.POINT_APPLICATION_REJECTED).contains(value))return true;
        var category=NotificationCategory.of(value);
        return switch(category){
            case ACTIVITY,PUBLIC_EVENT -> Set.of("targetId","subjectTitle","changesText","businessTime","location","activityId","activityTitle","startTime","publicEventStartTime","registrationStartTime","registrationDeadlineTime","cancellationReason").contains(source);
            case COMMENT -> Set.of("targetId","postId","commentId","originalContent","postTitle","replyUserName","replyContent","replyTime","actorName").contains(source);
            case REPLY -> Set.of("targetId","postId","originalContent","replyUserName","replyContent","replyTime","actorName","commentExcerpt").contains(source);
            case LIKE -> Set.of("actorName","subjectTitle").contains(source);
            case LEARNING_TASK -> Set.of("taskTitle","businessTime").contains(source);
            case POINTS,AUDIT_RESULT,SYSTEM -> false;
        };
    }
    public static Object read(Map<String,Object> frozen,String source){
        if("reviewResult".equals(source)) {
            Object result=frozen.get("auditResult");
            return result instanceof String value && Set.of("已通过","未通过").contains(value)?value:null;
        }
        if("reviewTime".equals(source))return frozen.get("auditTime");
        if("cancellationReason".equals(source))return frozen.get("presentation") instanceof Map<?,?> p?p.get("context"):null;
        if("activityTitle".equals(source)) {
            if(frozen.get("presentation") instanceof Map<?,?> p && p.get("subjectTitle")!=null)return p.get("subjectTitle");
            return frozen.get("activityTitle")==null?frozen.get("title"):frozen.get("activityTitle");
        }
        if(Set.of("subjectTitle","changesText","taskTitle","commentExcerpt").contains(source)){
            if(!(frozen.get("presentation") instanceof Map<?,?> p))return null;
            if("subjectTitle".equals(source))return p.get("subjectTitle");
            if("taskTitle".equals(source)||"commentExcerpt".equals(source))return p.get("body");
            if(!(p.get("changes") instanceof List<?> changes))return null;
            return changes.stream().filter(Map.class::isInstance).map(Map.class::cast)
                    .map(c->c.get("label")+": "+c.get("before")+" → "+c.get("after")).collect(java.util.stream.Collectors.joining("；"));
        }
        if("actorName".equals(source))return frozen.get("actor") instanceof Map<?,?> actor?actor.get("nickname"):null;
        return frozen.get(source);
    }
}
