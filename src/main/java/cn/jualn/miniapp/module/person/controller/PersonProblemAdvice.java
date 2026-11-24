package cn.jualn.miniapp.module.person.controller;

import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.common.exception.GlobalExceptionHandler;

import cn.dev33.satoken.exception.NotLoginException;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(-2)
@RestControllerAdvice(assignableTypes={PersonController.class,ManagedPersonController.class,MyPersonBindingController.class,PartyPersonnelController.class})
public class PersonProblemAdvice {
    private final GlobalExceptionHandler errors;
    public PersonProblemAdvice(GlobalExceptionHandler errors) { this.errors=errors; }
    @ExceptionHandler(ContractProblemException.class) public ResponseEntity<ProblemDetail> problem(ContractProblemException e) {
        if (e.getStatus()==HttpStatus.SERVICE_UNAVAILABLE && "/problems/people-source-unavailable".equals(e.getType())) {
            e=cn.jualn.miniapp.module.person.PersonProblems.unavailable();
        }
        return noStore(errors.handleContractProblem(e));
    }
    @ExceptionHandler(BusinessException.class) public ResponseEntity<ProblemDetail> business(BusinessException e) { return noStore(errors.handleBusiness(e)); }
    @ExceptionHandler(NotLoginException.class) public ResponseEntity<ProblemDetail> login(NotLoginException e) { return noStore(errors.handleNotLogin(e)); }
    @ExceptionHandler(HttpMessageNotReadableException.class) public ResponseEntity<ProblemDetail> input(HttpMessageNotReadableException e) { return noStore(errors.handleNotReadable(e)); }
    @ExceptionHandler(DataAccessException.class) public ResponseEntity<ProblemDetail> database(DataAccessException e) { return problem(cn.jualn.miniapp.module.person.PersonProblems.unavailable()); }
    private ResponseEntity<ProblemDetail> noStore(ResponseEntity<ProblemDetail> r) { return ResponseEntity.status(r.getStatusCode()).headers(r.getHeaders()).cacheControl(CacheControl.noStore()).body(r.getBody()); }
}
