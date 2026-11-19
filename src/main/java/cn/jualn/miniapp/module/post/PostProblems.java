package cn.jualn.miniapp.module.post;

import cn.jualn.miniapp.common.exception.ContractProblemException;
import org.springframework.http.HttpStatus;

public final class PostProblems {
    private PostProblems() {
    }

    public static ContractProblemException invalid(String field, String code, String detail) {
        return ContractProblemException.validation(new ContractProblemException.Violation("body", "/" + field, code, detail));
    }

    public static ContractProblemException problem(HttpStatus status, String type, String detail) {
        return new ContractProblemException(status, "/problems/" + type, detail);
    }

    public static void match(String supplied, String expected) {
        if (supplied == null) {
            throw ContractProblemException.preconditionRequired();
        }
        if (!supplied.matches("\"[^\"\\r\\n]+\"") || supplied.indexOf(',') >= 0) {
            throw ContractProblemException.validation(new ContractProblemException.Violation(
                    "header", "If-Match", "INVALID_ETAG", "Use one original strong ETag"));
        }
        if (!expected.equals(supplied)) {
            throw ContractProblemException.preconditionFailed();
        }
    }
}
