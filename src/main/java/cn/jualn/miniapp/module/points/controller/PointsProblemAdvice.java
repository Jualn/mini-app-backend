package cn.jualn.miniapp.module.points.controller;

import cn.dev33.satoken.exception.NotLoginException;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.common.exception.GlobalExceptionHandler;
import cn.jualn.miniapp.module.points.PointsProblems;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;

import org.springframework.core.annotation.Order;
import org.springframework.http.CacheControl;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@Order(-1)
@RestControllerAdvice(assignableTypes={PointsController.class,PointsFileController.class,PointAcceptanceSettingsController.class})
public class PointsProblemAdvice {
    private final GlobalExceptionHandler errors;
    public PointsProblemAdvice(GlobalExceptionHandler errors) { this.errors=errors; }
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ProblemDetail> sourceUnavailable(DataAccessException cause) {
        var failure=PointsProblems.unavailable(PointsProblems.Dependency.BUSINESS_ELIGIBILITY);
        failure.initCause(cause);
        return problem(failure);
    }
    @ExceptionHandler(ContractProblemException.class)
    public ResponseEntity<ProblemDetail> problem(ContractProblemException failure) {
        var response=errors.handleContractProblem(failure);
        if("/problems/points-dependency-unavailable".equals(failure.getType())) {
            if(failure.getStatus()==org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE) {
                // One diagnostic at the HTTP outcome boundary; never log private facts, SQL or exception messages.
                log.warn("result=failure errorCode=points-dependency-unavailable dependency={} exceptionType={} origin={} causeType={} causeProblemType={} causeOrigin={}",
                        dependency(failure),failure.getClass().getSimpleName(),origin(failure),
                        failure.getCause()==null?"none":failure.getCause().getClass().getSimpleName(),causeProblemType(failure.getCause()),origin(failure.getCause()));
            }
            response.getBody().setProperty("dependency",failure.getMessage());
            response.getBody().setDetail("Required Points capability is unavailable");
        }
        return noStore(response);
    }
    private static String dependency(ContractProblemException failure) {
        try {return PointsProblems.Dependency.valueOf(failure.getMessage()).name();}
        catch(IllegalArgumentException | NullPointerException unknown) {return "UNKNOWN";}
    }
    private static String origin(Throwable failure) {
        if(failure==null) return "none";
        return java.util.Arrays.stream(failure.getStackTrace())
                .filter(frame->frame.getClassName().startsWith("cn.jualn.miniapp.")
                        && !frame.getClassName().endsWith("Problems")
                        && !frame.getClassName().equals(ContractProblemException.class.getName())
                        && !"unavailable".equals(frame.getMethodName()))
                .findFirst().map(StackTraceElement::toString).orElse("unknown");
    }
    private static String causeProblemType(Throwable cause) {
        if(cause==null) return "none";
        if(cause instanceof ContractProblemException problem && problem.getType()!=null
                && problem.getType().matches("/problems/[a-z0-9-]{1,96}")) return problem.getType();
        return "unknown";
    }
    @ExceptionHandler(BusinessException.class) public ResponseEntity<ProblemDetail> business(BusinessException failure) { return noStore(errors.handleBusiness(failure)); }
    @ExceptionHandler(NotLoginException.class) public ResponseEntity<ProblemDetail> login(NotLoginException failure) { return noStore(errors.handleNotLogin(failure)); }
    @ExceptionHandler(HttpMessageNotReadableException.class) public ResponseEntity<ProblemDetail> unreadable(HttpMessageNotReadableException failure) { return noStore(errors.handleNotReadable(failure)); }
    private ResponseEntity<ProblemDetail> noStore(ResponseEntity<ProblemDetail> response) {
        return ResponseEntity.status(response.getStatusCode()).headers(response.getHeaders()).cacheControl(CacheControl.noStore()).body(response.getBody());
    }
}
