package cn.jualn.miniapp.module.media.service;

import cn.jualn.miniapp.common.exception.ContractProblemException;
import org.springframework.http.HttpStatus;

/**
 * The canonical private-material errors belong to this owner, without a dependency on Points orchestration.
 */
final class PointMaterialProblems {
    private PointMaterialProblems() {
    }

    enum Dependency {PRIVATE_MEDIA, CONTENT_SAFETY, BUSINESS_ELIGIBILITY}

    static ContractProblemException unavailable(Dependency dependency) {
        return new ContractProblemException(HttpStatus.SERVICE_UNAVAILABLE, "/problems/points-dependency-unavailable", dependency.name());
    }

    static ContractProblemException missing() {
        return new ContractProblemException(HttpStatus.NOT_FOUND, "/problems/not-found", "Resource not found");
    }

    static ContractProblemException conflict(String suffix) {
        return ContractProblemException.conflict("points-" + suffix, "Points operation conflicts with current facts");
    }

    static ContractProblemException invalid(String field) {
        return ContractProblemException.validation(new ContractProblemException.Violation("body", "/" + field, "INVALID_VALUE", "Invalid Points value"));
    }

    static ContractProblemException invalidParameter(String location, String field) {
        return ContractProblemException.validation(new ContractProblemException.Violation(location, field, "INVALID_VALUE", "Invalid Points parameter"));
    }
}
