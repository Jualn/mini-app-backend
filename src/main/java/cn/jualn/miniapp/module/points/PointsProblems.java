package cn.jualn.miniapp.module.points;

import cn.jualn.miniapp.common.exception.ContractProblemException;
import org.springframework.http.HttpStatus;

public final class PointsProblems {
    private PointsProblems() {
    }

    public enum Dependency {BUSINESS_ELIGIBILITY, PERSON_RESOLVER, AUTHORIZATION, PRIVATE_MEDIA, CONTENT_SAFETY, NOTIFICATION_ACCEPTANCE}

    public static ContractProblemException unavailable(Dependency dependency) {
        return new ContractProblemException(HttpStatus.SERVICE_UNAVAILABLE, "/problems/points-dependency-unavailable", dependency.name());
    }

    public static ContractProblemException conflict(String suffix) {
        return ContractProblemException.conflict("points-" + suffix, "Points operation conflicts with current facts");
    }

    public static ContractProblemException forbidden(String suffix) {
        return new ContractProblemException(HttpStatus.FORBIDDEN, "/problems/points-" + suffix, "Points operation is not permitted");
    }

    public static ContractProblemException missing() {
        return new ContractProblemException(HttpStatus.NOT_FOUND, "/problems/not-found", "Resource not found");
    }

    public static ContractProblemException invalid(String field) {
        return ContractProblemException.validation(new ContractProblemException.Violation("body", "/" + field.replace("~", "~0").replace("/", "~1"), "INVALID_VALUE", "Invalid Points value"));
    }

    public static ContractProblemException invalidParameter(String location, String name) {
        return ContractProblemException.validation(new ContractProblemException.Violation(location, name, "INVALID_VALUE", "Invalid Points parameter"));
    }
}
