package cn.jualn.miniapp.module.learningtask.controller;

import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.exception.GlobalExceptionHandler;
import cn.dev33.satoken.exception.NotLoginException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.core.annotation.Order;
import org.springframework.http.CacheControl;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Canonical dependency extension is local to LearningTask, not a new global error convention. */
@Order(-1)
@RestControllerAdvice(assignableTypes={LearningTaskController.class,LearningTaskDraftController.class,LearningTaskSliceController.class,LearningTaskSubmissionController.class,LearningTaskArticleOptionsController.class,LearningTaskOptionsController.class,LearningTaskSelectedRecipientsController.class})
public class LearningTaskProblemAdvice {
    private final GlobalExceptionHandler errors;
    public LearningTaskProblemAdvice(GlobalExceptionHandler errors) { this.errors=errors; }
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ProblemDetail> business(BusinessException exception) {
        var response=errors.handleBusiness(exception);
        return ResponseEntity.status(response.getStatusCode()).headers(response.getHeaders()).cacheControl(CacheControl.noStore()).body(response.getBody());
    }
    @ExceptionHandler(NotLoginException.class)
    public ResponseEntity<ProblemDetail> unauthenticated(NotLoginException exception) {
        var response=errors.handleNotLogin(exception);
        return ResponseEntity.status(response.getStatusCode()).headers(response.getHeaders()).cacheControl(CacheControl.noStore()).body(response.getBody());
    }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> unreadable(HttpMessageNotReadableException exception) {
        var response=errors.handleNotReadable(exception);
        return ResponseEntity.status(response.getStatusCode()).headers(response.getHeaders()).cacheControl(CacheControl.noStore()).body(response.getBody());
    }
    @ExceptionHandler(ContractProblemException.class)
    public ResponseEntity<ProblemDetail> problem(ContractProblemException exception) {
        var response=errors.handleContractProblem(exception);
        if("/problems/learning-task-dependency-unavailable".equals(exception.getType())) {
            response.getBody().setProperty("dependency",exception.getMessage());
            response.getBody().setDetail("Required LearningTask capability is unavailable");
        }
        return ResponseEntity.status(response.getStatusCode()).headers(response.getHeaders()).cacheControl(CacheControl.noStore()).body(response.getBody());
    }
}
