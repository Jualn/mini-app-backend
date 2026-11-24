package cn.jualn.miniapp.module.admin.operation.service;
import java.util.*;
import org.springframework.web.context.request.*;
import org.springframework.transaction.support.*;
/** Only owner-confirmed identifiers cross the HTTP completion boundary. No body, key, token or arbitrary context. */
public final class AdminAuditAttemptContext {
    private AdminAuditAttemptContext() {}
    public static final String ATTRIBUTE=AdminAuditAttemptContext.class.getName();
    public static final class Attempt {
        public final long actor;public final String operation,type,target,id=UUID.randomUUID().toString();
        public String successEvent;public int completion=TransactionSynchronization.STATUS_UNKNOWN;
        Attempt(long actor,String operation,String type,String target) {this.actor=actor;this.operation=operation;this.type=type;this.target=target;}
    }
    @SuppressWarnings("unchecked")
    public static Attempt confirmed(long actor,String operation,String type,String target) {
        var attributes=RequestContextHolder.getRequestAttributes();
        if(!(attributes instanceof ServletRequestAttributes servlet)) return null;
        var request=servlet.getRequest();
        var attempts=(List<Attempt>)request.getAttribute(ATTRIBUTE);
        if(attempts==null) {attempts=new ArrayList<>();request.setAttribute(ATTRIBUTE,attempts);}
        for(var a:attempts)if(a.actor==actor && a.operation.equals(operation) && a.type.equals(type) && a.target.equals(target))return a;
        var attempt=new Attempt(actor,operation,type,target);attempts.add(attempt);
        if(TransactionSynchronizationManager.isSynchronizationActive())TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {attempt.completion=status;}
        });
        return attempt;
    }
}
