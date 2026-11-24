package cn.jualn.miniapp.module.admin.operation.controller;
import cn.jualn.miniapp.common.exception.*;
import cn.jualn.miniapp.module.admin.operation.service.AdminAuditQueries;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
@RestControllerAdvice(assignableTypes=AdminAuditController.class)
@org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class AdminAuditAdvice {
    private final GlobalExceptionHandler common;
    @ExceptionHandler(ContractProblemException.class) public ResponseEntity<ProblemDetail> problem(ContractProblemException failure) {
        var response=common.handleContractProblem(failure);
        return ResponseEntity.status(response.getStatusCode()).cacheControl(CacheControl.noStore()).body(response.getBody());
    }
    @ExceptionHandler({org.springframework.dao.DataAccessException.class,org.springframework.transaction.TransactionException.class})
    public ResponseEntity<ProblemDetail> unavailable(Exception failure) {return problem(AdminAuditQueries.unavailable());}
    @ExceptionHandler({org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,org.springframework.web.bind.MissingServletRequestParameterException.class})
    public ResponseEntity<ProblemDetail> invalid(Exception failure) {return problem(AdminAuditQueries.invalid());}
}
