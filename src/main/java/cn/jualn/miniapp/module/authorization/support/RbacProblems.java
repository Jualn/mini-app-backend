package cn.jualn.miniapp.module.authorization.support;

import cn.jualn.miniapp.common.exception.ContractProblemException;
import org.springframework.http.HttpStatus;

/**
 * Module-internal error construction; not an authorization decision or grant capability.
 */
public final class RbacProblems {
    private RbacProblems() {
    }

    public static ContractProblemException unavailable() {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "authorization-source-unavailable");
    }

    public static ContractProblemException problem(HttpStatus status, String type) {
        return new ContractProblemException(status, "/problems/" + type, "Authorization request cannot be completed");
    }

    public static ContractProblemException invalid(String field) {
        return ContractProblemException.validation(new ContractProblemException.Violation("body", "/" + field, "INVALID", "Invalid authorization input"));
    }

    public static ContractProblemException invalidQuery(String field) {
        return ContractProblemException.validation(new ContractProblemException.Violation("query", "/" + field, "INVALID", "Invalid authorization query"));
    }
}
