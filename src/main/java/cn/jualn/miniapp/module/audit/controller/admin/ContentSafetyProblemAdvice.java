package cn.jualn.miniapp.module.audit.controller.admin;
import cn.jualn.miniapp.common.exception.*;
import cn.jualn.miniapp.module.audit.service.ReviewConflict;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.core.annotation.Order;
import org.springframework.dao.*;
@RestControllerAdvice(assignableTypes=AdminReviewController.class) @Order(0)
public class ContentSafetyProblemAdvice {
    private final GlobalExceptionHandler errors;
    public ContentSafetyProblemAdvice(GlobalExceptionHandler errors) {this.errors=errors;}
    @ExceptionHandler(ReviewConflict.class)
    public ResponseEntity<ProblemDetail> conflict(ReviewConflict failure) {
        var response=errors.handleContractProblem(ContractProblemException.conflict("conflict",failure.getMessage()));
        response.getBody().setProperty("conflictCode",failure.getConflictCode());return noStore(response);
    }
    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<ProblemDetail> duplicate(DuplicateKeyException failure) {return conflict(new ReviewConflict("review-idempotency-conflict"));}
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ProblemDetail> unavailable(DataAccessException failure) {
        return noStore(errors.handleContractProblem(new ContractProblemException(HttpStatus.SERVICE_UNAVAILABLE,"/problems/service-unavailable","Content safety storage unavailable")));
    }
    @ExceptionHandler(ContractProblemException.class)
    public ResponseEntity<ProblemDetail> contract(ContractProblemException failure) {return noStore(errors.handleContractProblem(failure));}
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ProblemDetail> business(BusinessException failure) {return noStore(errors.handleBusiness(failure));}
    private ResponseEntity<ProblemDetail> noStore(ResponseEntity<ProblemDetail> response) {return ResponseEntity.status(response.getStatusCode()).headers(response.getHeaders()).cacheControl(CacheControl.noStore()).body(response.getBody());}
}
