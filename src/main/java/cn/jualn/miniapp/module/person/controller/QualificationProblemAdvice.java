package cn.jualn.miniapp.module.person.controller;

import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.common.exception.GlobalExceptionHandler;

import cn.jualn.miniapp.module.person.service.support.PersonnelValues;
import org.springframework.core.annotation.Order;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import org.springframework.dao.DataAccessException;

/** SQL/driver messages may contain protected bind values; never expose or log them here. */
@Order(-3) @RestControllerAdvice(assignableTypes=QualificationController.class)
public class QualificationProblemAdvice {
    private final GlobalExceptionHandler errors;
    public QualificationProblemAdvice(GlobalExceptionHandler errors) {this.errors=errors;}
    @ExceptionHandler(ContractProblemException.class)
    public ResponseEntity<ProblemDetail> problem(ContractProblemException e) {
        var mapped=e.getStatus()==HttpStatus.SERVICE_UNAVAILABLE?PersonnelValues.unavailable()
            :e.getStatus()==HttpStatus.FORBIDDEN?PersonnelValues.problem(HttpStatus.FORBIDDEN,"forbidden"):e;
        return noStore(errors.handleContractProblem(mapped));
    }
    @ExceptionHandler(org.springframework.dao.DuplicateKeyException.class)
    public ResponseEntity<ProblemDetail> duplicate(org.springframework.dao.DuplicateKeyException e) {return problem(PersonnelValues.conflict());}
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ProblemDetail> database(DataAccessException e) {return problem(PersonnelValues.unavailable());}
    private ResponseEntity<ProblemDetail> noStore(ResponseEntity<ProblemDetail> response) {return ResponseEntity.status(response.getStatusCode()).headers(response.getHeaders()).cacheControl(CacheControl.noStore()).body(response.getBody());}
}
