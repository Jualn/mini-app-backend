package cn.jualn.miniapp.module.authorization.web;

import cn.jualn.miniapp.module.authorization.support.UnifiedProblems;
import cn.jualn.miniapp.module.authorization.support.RbacProblems;
import cn.jualn.miniapp.module.authorization.service.UnifiedAuthorizationService;
import cn.jualn.miniapp.common.exception.*;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.*;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Protocol mapping limited to the nine RBAC operations; old failure semantics remain unchanged.
 */
@RestControllerAdvice(assignableTypes = {RbacController.class, UnifiedAuthorizationController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class RbacExceptionHandler {
    private final GlobalExceptionHandler common;

    @ExceptionHandler(ContractProblemException.class)
    public ResponseEntity<ProblemDetail> contract(ContractProblemException failure) {
        var attributes = RequestContextHolder.getRequestAttributes();
        if (failure.getStatus() == HttpStatus.SERVICE_UNAVAILABLE && attributes instanceof org.springframework.web.context.request.ServletRequestAttributes servlet) {
            String path = servlet.getRequest().getRequestURI();
            if (path.endsWith("/target-options") || path.endsWith("/recipient-options"))
                failure = UnifiedProblems.problem(HttpStatus.SERVICE_UNAVAILABLE, "unified-authorization-unavailable");
            else if (path.endsWith("/duty-options") || path.endsWith("/effective-duties") || path.contains("/duty-previews")
                    || path.endsWith("/duty-changes") || path.endsWith("/delegation-authorities"))
                failure = UnifiedProblems.unavailable();
        }
        var response = common.handleContractProblem(failure);
        return ResponseEntity.status(response.getStatusCode()).headers(response.getHeaders()).cacheControl(CacheControl.noStore()).body(response.getBody());
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MissingRequestHeaderException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ProblemDetail> input(Exception failure) {
        if (failure instanceof MissingRequestHeaderException header)
            return contract(ContractProblemException.validation(
                    new ContractProblemException.Violation("header", "/" + header.getHeaderName(), "REQUIRED", "Required authorization header")));
        if (failure instanceof MethodArgumentTypeMismatchException parameter)
            return contract(RbacProblems.invalidQuery(parameter.getName()));
        return contract(RbacProblems.invalid("request"));
    }

    @ExceptionHandler({DataAccessException.class, TransactionException.class,
            RedisConnectionFailureException.class})
    public ResponseEntity<ProblemDetail> unavailable(Exception failure) {
        return contract(RbacProblems.unavailable());
    }
}
