package cn.jualn.miniapp.module.authorization.support;

import cn.jualn.miniapp.common.exception.ContractProblemException;
import org.springframework.http.HttpStatus;

/** Module-internal error representation shared by application and HTTP boundaries. */
public final class UnifiedProblems {
    private UnifiedProblems() {}
    public static ContractProblemException problem(HttpStatus status,String type) {return new ContractProblemException(status,"/problems/"+type,"Unified authorization operation cannot be completed");}
    public static ContractProblemException forbidden() {return problem(HttpStatus.FORBIDDEN,"forbidden");}
    public static ContractProblemException missing() {return problem(HttpStatus.NOT_FOUND,"not-found");}
    public static ContractProblemException unavailable() {return problem(HttpStatus.SERVICE_UNAVAILABLE,"unified-dependency-unavailable");}
    public static ContractProblemException stale() {return problem(HttpStatus.CONFLICT,"unified-preview-stale");}
}
