package cn.jualn.miniapp.module.points.service;

import cn.jualn.miniapp.module.person.service.access.PersonAuthorityGate;

import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import cn.jualn.miniapp.module.points.PointsProblems.Dependency;
import cn.jualn.miniapp.module.notify.enums.NotifyType;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.common.observability.ObservabilityContext;
import cn.jualn.miniapp.infrastructure.async.job.JobDefinition;
import cn.jualn.miniapp.infrastructure.async.job.JobExecutionContext;
import cn.jualn.miniapp.infrastructure.async.job.JobService;

import cn.jualn.miniapp.module.authorization.service.TransactionalAuthorization;
import cn.jualn.miniapp.module.notify.bo.NotificationCenterBO;
import cn.jualn.miniapp.module.notify.payload.NotifyPayload;
import cn.jualn.miniapp.module.notify.service.NotifyService;
import cn.jualn.miniapp.module.points.bo.PointsBO.Application;
import cn.jualn.miniapp.module.points.bo.PointsBO.Kind;
import cn.jualn.miniapp.module.points.bo.PointsBO.Ledger;
import cn.jualn.miniapp.module.points.bo.PointsBO.Status;

import cn.jualn.miniapp.module.user.service.UserCurrentLoginAccess;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static cn.jualn.miniapp.module.points.PointsProblems.unavailable;

/** Accounting commits the durable intent; the owned worker writes only the local inbox. */
@Service
public class PointsResultNotifications {
    public static final String JOB="points.result-notification";
    public record Result(long applicationId,Long userId,String type,String occurrence,String personId,String bindingId,String recipientStatus) {
        public Result(long applicationId,long userId,String type,String occurrence) {this(applicationId,userId,type,occurrence,null,null,null);}
    }
    @org.springframework.beans.factory.annotation.Autowired
    private cn.jualn.miniapp.module.person.service.access.PersonBusinessScopes persons;
    @org.springframework.beans.factory.annotation.Autowired
    private cn.jualn.miniapp.module.points.mapper.PointsMapper points;
    private final JobService jobs;
    private final NotifyService notifications;
    private final TransactionalAuthorization users;
    private final UserCurrentLoginAccess login;
    public PointsResultNotifications(JobService jobs,NotifyService notifications,TransactionalAuthorization users,UserCurrentLoginAccess login) {
        this.jobs=jobs;this.notifications=notifications;this.users=users;this.login=login;
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void accept(Application app,Ledger reversal) {
        if(app==null || app.status()!=Status.APPROVED && app.status()!=Status.REJECTED || reversal!=null &&
                (reversal.kind()!=Kind.REVERSAL || reversal.applicationId()!=app.applicationId()
                        || reversal.applicantUserId()!=app.applicantUserId() || app.status()!=Status.APPROVED))
            throw new IllegalArgumentException("Points notification requires a matching terminal business fact");
        String type=reversal!=null?"POINT_GRANT_REVERSED":app.status()==Status.APPROVED?"POINT_APPLICATION_APPROVED":"POINT_APPLICATION_REJECTED";
        String occurrence=reversal!=null?"reversal:"+reversal.ledgerId():"application:"+app.applicationId()+":"+app.status();
        enqueue(app,type,occurrence);
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void acceptCorrection(Application app,cn.jualn.miniapp.module.points.bo.PointsBO.Correction correction) {
        if(app.status()!=Status.APPROVED || correction.applicationId()!=app.applicationId()) throw new IllegalArgumentException("Mismatched correction");
        enqueue(app,"POINT_GRANT_CORRECTED","correction:"+correction.correctionId());
    }
    private void enqueue(Application app,String type,String occurrence) {
        var binding=persons.deliveryBinding(app.personId());
        if(binding==null || "VERIFICATION_REQUIRED".equals(binding.status())) throw unavailable(Dependency.NOTIFICATION_ACCEPTANCE);
        String recipient=!"ACTIVE".equals(binding.status())?"PERSON_UNAVAILABLE":binding.userId()==null?"NO_BINDING":"BOUND";
        var payload=new Result(app.applicationId(),binding.userId(),type,occurrence,app.personId(),binding.bindingId(),recipient);
        try { jobs.create(new JobDefinition(JOB,2,ObservabilityContext.ensureOperationId(),occurrence,"point-application",
                Long.toString(app.applicationId()),payload,null,6)); }
        catch(org.springframework.dao.DataAccessException failure) { var problem=unavailable(Dependency.NOTIFICATION_ACCEPTANCE);problem.initCause(failure);throw problem; }
    }
    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public void deliver(Result result,JobExecutionContext context) {
        if(context==null) throw new IllegalArgumentException("Points notification requires job ownership");
        if(result.personId()==null || result.recipientStatus()==null) throw new IllegalStateException("Points recipient binding evidence missing");
        users.begin(PersonAuthorityGate.Access.SHARED);
        context.requireOwnership();
        if(!"BOUND".equals(result.recipientStatus())) {resolve(result,result.recipientStatus());return;}
        var current=persons.deliveryBinding(result.personId());
        if(current==null || "VERIFICATION_REQUIRED".equals(current.status())) throw new IllegalStateException("Points recipient binding cannot be verified");
        if(!"ACTIVE".equals(current.status())) {resolve(result,"PERSON_UNAVAILABLE");return;}
        if(!java.util.Objects.equals(current.userId(),result.userId()) || !java.util.Objects.equals(current.bindingId(),result.bindingId())) {resolve(result,"BINDING_CHANGED");return;}
        try { login.require(result.userId()); }
        catch(BusinessException unavailableRecipient) {
            if(unavailableRecipient.getResultCode()==ResultCode.USER_BANNED
                    || unavailableRecipient.getResultCode()==ResultCode.USER_NOT_FOUND) {resolve(result,"ACCOUNT_UNAVAILABLE");return;}
            // The generic worker treats BusinessException as a permanent validation error.
            // Unverified account facts are an infrastructure retry, never recipient denial.
            throw new IllegalStateException("Notification recipient account cannot be verified: "
                    +unavailableRecipient.getResultCode().name());
        }
        context.requireOwnership();
        var type=NotifyType.valueOf(result.type());
        if(type!=NotifyType.POINT_APPLICATION_APPROVED && type!=NotifyType.POINT_APPLICATION_REJECTED && type!=NotifyType.POINT_GRANT_REVERSED && type!=NotifyType.POINT_GRANT_CORRECTED)
            throw new IllegalArgumentException("Unknown Points result type");
        // No material, signature, reviewer note or deep link in the frozen notification.
        var snapshot=new NotificationCenterBO.Snapshot(new NotificationCenterBO.Presentation(type.getDesc(),"请查看积分申请与积分记录。",null,null,null),
                null,new NotificationCenterBO.Subject("POINT_APPLICATION",Long.toString(result.applicationId())),null);
        notifications.processNotificationPayload(NotifyPayload.builder().receiverId(result.userId()).type(type)
                .title(type.getDesc()).content("请查看积分申请与积分记录。").sourceKey("points:"+result.occurrence())
                .snapshot(snapshot).inAppOnly(true).build());
        resolve(result,"INBOX_ACCEPTED");
    }
    private void resolve(Result result,String outcome) {
        points.notificationResolution(result.occurrence(),outcome,java.time.LocalDateTime.now(java.time.ZoneOffset.UTC));
    }
}
