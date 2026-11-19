package cn.jualn.miniapp.module.admin.operation.controller;
import cn.jualn.miniapp.module.admin.operation.service.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
@Component @RequiredArgsConstructor @Slf4j
public class AdminAuditAttemptFilter extends OncePerRequestFilter {
    private final AdminAuditAttemptSink sink;
    @Override @SuppressWarnings("unchecked")
    protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        boolean transportFailure=false;
        try {chain.doFilter(request,response);}
        catch(ServletException|IOException|RuntimeException failure) {transportFailure=true;throw failure;}
        finally {
            var attempts=(List<AdminAuditAttemptContext.Attempt>)request.getAttribute(AdminAuditAttemptContext.ATTRIBUTE);
            int status=transportFailure?500:response.getStatus();
            if(attempts!=null && status>=400)for(var attempt:attempts) {
                if(attempt.completion==org.springframework.transaction.support.TransactionSynchronization.STATUS_COMMITTED) continue;
                try {sink.accept(attempt,status);}
                catch(RuntimeException failure) {log.error("[AdminAuditAttemptFilter][Audit acceptance unavailable] result=failure, errorCategory=database, exceptionType={}",failure.getClass().getSimpleName());}
            }
        }
    }
}
