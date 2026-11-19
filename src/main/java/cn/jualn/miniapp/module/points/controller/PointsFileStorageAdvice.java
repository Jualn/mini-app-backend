package cn.jualn.miniapp.module.points.controller;

import cn.jualn.miniapp.module.points.PointsProblems;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** A missing trusted file association is an unavailable owner, never an empty/public fallback. */
@Order(-2)
@RestControllerAdvice(assignableTypes=PointsFileController.class)
public class PointsFileStorageAdvice {
    private final PointsProblemAdvice points;
    public PointsFileStorageAdvice(PointsProblemAdvice points) { this.points=points; }
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ProblemDetail> unavailable(DataAccessException failure) {
        var problem=PointsProblems.unavailable(PointsProblems.Dependency.PRIVATE_MEDIA);problem.initCause(failure);
        return points.problem(problem);
    }
}
