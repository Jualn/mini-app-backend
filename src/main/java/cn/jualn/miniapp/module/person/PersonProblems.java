package cn.jualn.miniapp.module.person;

import cn.jualn.miniapp.common.exception.ContractProblemException;
import org.springframework.http.HttpStatus;

public final class PersonProblems {
    private PersonProblems() {
    }

    public static ContractProblemException unavailable() {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "person-foundation-unavailable");
    }

    public static ContractProblemException missing() {
        return problem(HttpStatus.NOT_FOUND, "not-found");
    }

    public static ContractProblemException denied() {
        return problem(HttpStatus.FORBIDDEN, "forbidden");
    }

    public static ContractProblemException conflict() {
        return problem(HttpStatus.CONFLICT, "person-state-conflict");
    }

    public static ContractProblemException problem(HttpStatus status, String type) {
        return new ContractProblemException(status, "/problems/" + type, "Personnel operation cannot be completed");
    }

    public static ContractProblemException invalid(String field) {
        return ContractProblemException.validation(new ContractProblemException.Violation("body", "/" + field, "INVALID_VALUE", "Invalid personnel value"));
    }
}
