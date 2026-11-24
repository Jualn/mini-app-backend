package cn.jualn.miniapp.module.learningtask;

import cn.jualn.miniapp.common.exception.ContractProblemException;
import org.springframework.http.HttpStatus;

public final class LearningTaskProblems {
    private LearningTaskProblems() {
    }

    public enum Dependency {PERSON_RESOLVER, AUTHORIZATION, PRIVATE_MEDIA, CONTENT_SAFETY, NOTIFICATION_ACCEPTANCE}

    public static ContractProblemException unavailable(Dependency dependency) {
        // The controller advice adds the required canonical extension, without changing global exceptions.
        return new ContractProblemException(HttpStatus.SERVICE_UNAVAILABLE,
                "/problems/learning-task-dependency-unavailable", dependency.name());
    }

    public static ContractProblemException conflict(String suffix) {
        return ContractProblemException.conflict("learning-task-" + suffix, "LearningTask operation conflicts with current facts");
    }

    public static ContractProblemException invalid(String field) {
        return ContractProblemException.validation(new ContractProblemException.Violation("body", "/" + field,
                "INVALID_VALUE", "Invalid LearningTask value"));
    }

    public static ContractProblemException missing() {
        return new ContractProblemException(HttpStatus.NOT_FOUND, "/problems/not-found", "Resource not found");
    }
}
