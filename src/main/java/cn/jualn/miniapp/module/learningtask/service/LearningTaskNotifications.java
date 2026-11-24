package cn.jualn.miniapp.module.learningtask.service;

import cn.jualn.miniapp.module.person.service.access.PersonBusinessScopes;

import cn.jualn.miniapp.module.notify.enums.NotifyType;
import cn.jualn.miniapp.common.observability.ObservabilityContext;
import cn.jualn.miniapp.infrastructure.async.job.JobDefinition;
import cn.jualn.miniapp.infrastructure.async.job.JobService;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Notice;
import cn.jualn.miniapp.module.learningtask.entity.LearningAssignment;
import cn.jualn.miniapp.module.learningtask.entity.LearningTask;
import cn.jualn.miniapp.module.learningtask.mapper.LearningAssignmentMapper;
import cn.jualn.miniapp.module.learningtask.mapper.LearningTaskLedgerMapper;
import cn.jualn.miniapp.module.learningtask.mapper.LearningTaskMapper;
import cn.jualn.miniapp.module.notify.bo.NotificationCenterBO;
import cn.jualn.miniapp.module.notify.payload.NotifyPayload;
import cn.jualn.miniapp.module.notify.service.NotifyService;
import cn.jualn.miniapp.module.user.service.UserLearningTaskRecipientService;
import cn.jualn.miniapp.common.exception.BusinessException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Local IN_APP acceptance and existing durable Job scheduling; never remote delivery. */
@Service
public class LearningTaskNotifications {
    public static final String REMINDER_JOB="learning-task.reminder";
    public record Reminder(long taskId,long assignmentId,long userId,long revision,String deadline,String kind,String title,cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Subject subject,String bindingId) {}
    @org.springframework.beans.factory.annotation.Autowired private LearningTaskResponsibilities responsibilities;
    @org.springframework.beans.factory.annotation.Autowired private cn.jualn.miniapp.module.learningtask.mapper.LearningTaskNoticeMapper noticeLedger;
    @org.springframework.beans.factory.annotation.Autowired private cn.jualn.miniapp.module.person.service.access.PersonBusinessScopes persons;
    private final LearningTaskMapper tasks;
    private final LearningAssignmentMapper assignments;
    private final LearningTaskLedgerMapper ledger;
    private final NotifyService notifications;
    private final JobService jobs;
    private final Clock clock;
    private final UserLearningTaskRecipientService recipients;
    public LearningTaskNotifications(LearningTaskMapper tasks,LearningAssignmentMapper assignments,LearningTaskLedgerMapper ledger,
            NotifyService notifications,JobService jobs,@Qualifier("learningTaskClock") Clock clock,UserLearningTaskRecipientService recipients) {
        this.tasks=tasks; this.assignments=assignments; this.ledger=ledger; this.notifications=notifications;
        this.jobs=jobs; this.clock=clock; this.recipients=recipients;
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void accept(List<Notice> notices) {
        for(Notice notice:notices) {
            noticeLedger.retain(notice);
            if(!"AVAILABLE".equals(notice.availability()) || notice.userId()==null) continue;
            NotifyType type=NotifyType.valueOf("LEARNING_TASK_"+notice.type());
            var changes="DEADLINE_CHANGED".equals(notice.type())
                    ? List.of(new NotificationCenterBO.Change("首次截止",wire(notice.beforeDeadline()),wire(notice.afterDeadline()))) : null;
            var snapshot=new NotificationCenterBO.Snapshot(new NotificationCenterBO.Presentation(type.getDesc(),notice.title(),null,null,changes),
                    null,new NotificationCenterBO.Subject("LEARNING_TASK",Long.toString(notice.taskId())),
                    new NotificationCenterBO.Target("LEARNING_TASK_DETAIL",null,null,null,null,null,Long.toString(notice.taskId()),Long.toString(notice.assignmentId())));
            notifications.processNotificationPayload(NotifyPayload.builder().receiverId(notice.userId()).type(type)
                    .title(type.getDesc()).content(notice.title()).targetId(notice.taskId()).sourceKey("learning-task:"+notice.occurrence())
                    .snapshot(snapshot).contentData(type==NotifyType.LEARNING_TASK_REVIEW_APPROVED || type==NotifyType.LEARNING_TASK_REVIEW_REJECTED
                            ? new cn.jualn.miniapp.module.notify.payload.data.AuditResultNoticeData("学习任务",type==NotifyType.LEARNING_TASK_REVIEW_APPROVED?"已通过":"未通过","",LocalDateTime.now(clock.withZone(ZoneId.of("Asia/Shanghai")))) : null)
                    .recipientPersonId(notice.subject()==null?null:notice.subject().personId()).recipientBindingId(notice.bindingId())
                    .businessTime(type==NotifyType.LEARNING_TASK_SUPPLEMENT_DEADLINE_REMINDER?notice.supplementDeadline():notice.afterDeadline()).build());
        }
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void reconcile(long taskId,LocalDateTime now) {
        LearningTask task=tasks.lock(taskId);
        if(task==null) { return; }
        for(var assignment:ledger.assignments(taskId)) {
            reconcile(task,assignment,"FIRST_DEADLINE_REMINDER",task.getFirstDeadline(),task.getDeadlineRevision(),now);
            if(assignment.getSupplementDeadline()!=null) {
                reconcile(task,assignment,"SUPPLEMENT_DEADLINE_REMINDER",assignment.getSupplementDeadline(),0,now);
            }
        }
    }
    private void reconcile(LearningTask task,LearningAssignment assignment,String kind,LocalDateTime deadline,long revision,LocalDateTime now) {
        var target=responsibilities.recipient(subject(assignment));if(!"AVAILABLE".equals(target.availability())) return;
        Reminder candidate=new Reminder(task.getId(),assignment.getId(),target.deliveryUserId(),revision,deadline.toString(),kind,task.getTitle(),subject(assignment),target.bindingId());
        String key=key(candidate);
        if(!eligible(task,assignment,kind)) { jobs.cancelPending(REMINDER_JOB,key); return; }
        LocalDateTime due=deadline.minusHours(24);
        if(!due.isAfter(now)) { return; }
        jobs.create(new JobDefinition(REMINDER_JOB,1,ObservabilityContext.ensureOperationId(),key,"learning-task-assignment",
                assignment.getId().toString(),candidate,due,6));
    }
    @Transactional
    public void deliver(Reminder candidate) {
        deliverInternal(candidate,null);
    }
    @Transactional
    public void deliver(Reminder candidate,cn.jualn.miniapp.infrastructure.async.job.JobExecutionContext context) {
        deliverInternal(candidate,context);
    }
    private void deliverInternal(Reminder candidate,cn.jualn.miniapp.infrastructure.async.job.JobExecutionContext context) {
        persons.lock(false);
        if(candidate.subject()==null) {
            candidate=new Reminder(candidate.taskId(),candidate.assignmentId(),candidate.userId(),candidate.revision(),candidate.deadline(),candidate.kind(),candidate.title(),cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Subject.account(candidate.userId()),null);
        }
        var target=responsibilities.recipient(candidate.subject());
        if(!"AVAILABLE".equals(target.availability()) || !Long.valueOf(candidate.userId()).equals(target.deliveryUserId()) || !java.util.Objects.equals(candidate.bindingId(),target.bindingId())) return;
        LearningTask task=tasks.lock(candidate.taskId());
        if(task==null) { return; }
        LearningAssignment assignment=assignments.lock(candidate.assignmentId());
        if(assignment==null || !assignment.getTaskId().equals(task.getId()) || !subject(assignment).equals(candidate.subject())
                || !eligible(task,assignment,candidate.kind())) { return; }
        LocalDateTime deadline="FIRST_DEADLINE_REMINDER".equals(candidate.kind())?task.getFirstDeadline():assignment.getSupplementDeadline();
        LocalDateTime now=LocalDateTime.ofInstant(clock.instant(),ZoneId.of("Asia/Shanghai"));
        if(deadline==null || !deadline.toString().equals(candidate.deadline()) || !now.isBefore(deadline)
                || now.isBefore(deadline.minusHours(24))
                || "FIRST_DEADLINE_REMINDER".equals(candidate.kind()) && task.getDeadlineRevision()!=candidate.revision()) { return; }
        try { recipients.requireEligible(List.of(candidate.userId())); }
        catch(BusinessException ineligible) { return; }
        if(context!=null) { context.requireOwnership(); }
        accept(List.of(new Notice(key(candidate),candidate.kind(),task.getId(),assignment.getId(),candidate.userId(),
                candidate.title(),null,task.getFirstDeadline(),assignment.getSupplementDeadline(),candidate.subject(),candidate.bindingId())));
    }
    private cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Subject subject(LearningAssignment assignment) {
        return "PERSON".equals(assignment.getSubjectType())?cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Subject.person(assignment.getPersonId()):cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Subject.account(assignment.getUserId());
    }
    private boolean eligible(LearningTask task,LearningAssignment assignment,String kind) {
        if(!"PUBLISHED".equals(task.getStatus()) || !"ACTIVE".equals(assignment.getAllocationStatus())) { return false; }
        return switch(kind) {
            case "FIRST_DEADLINE_REMINDER" -> "NOT_STARTED".equals(assignment.getProgress())
                    && assignment.getConfirmedAt()==null && assignment.getFirstSubmittedAt()==null;
            case "SUPPLEMENT_DEADLINE_REMINDER" -> "REJECTED".equals(assignment.getProgress()) && assignment.getCompletedAt()==null;
            default -> false;
        };
    }
    private String key(Reminder candidate) {
        return candidate.assignmentId()+":"+candidate.kind()+":"+("FIRST_DEADLINE_REMINDER".equals(candidate.kind())?candidate.revision():candidate.deadline());
    }
    private String wire(LocalDateTime time) { return time==null?null:time.atZone(ZoneId.of("Asia/Shanghai")).format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME); }
}
