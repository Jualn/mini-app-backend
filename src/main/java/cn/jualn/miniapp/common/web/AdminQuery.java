package cn.jualn.miniapp.common.web;

import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.common.result.ResultCode;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.servlet.http.HttpServletRequest;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;

/**
 * Bounded read input and safe partial-failure representation shared by admin queries.
 */
@Slf4j
public final class AdminQuery {
    private AdminQuery() {
    }

    public static List<String> values(HttpServletRequest request, String key, int maximum) {
        if (!request.getParameterMap().keySet().equals(Set.of(key))) {
            throw invalid(key);
        }
        var raw = request.getParameterValues(key);
        if (raw == null) {
            throw invalid(key);
        }
        return validate(List.of(raw), key, maximum);
    }

    public static List<String> validate(List<String> values, String key, int maximum) {
        if (values == null || values.isEmpty() || values.size() > maximum
                || values.stream().anyMatch(v -> v == null || v.isEmpty() || v.length() > 128)
                || new HashSet<>(values).size() != values.size()) {
            throw invalid(key);
        }
        return List.copyOf(values);
    }

    public static long numericId(String value, String key) {
        try {
            if (!value.matches("[1-9][0-9]*")) {
                throw invalid(key);
            }
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw invalid(key);
        }
    }

    public static Long storedId(String value) {
        try {
            return numericId(value, "id");
        } catch (ContractProblemException failure) {
            return null;
        }
    }

    public static ContractProblemException invalid(String key) {
        return ContractProblemException.validation(new ContractProblemException.Violation(
                "query", "/" + key, "INVALID", "Invalid bounded query"));
    }

    public static ContractProblemException unavailable() {
        return new ContractProblemException(HttpStatus.SERVICE_UNAVAILABLE,
                "/problems/service-unavailable", "Read unavailable");
    }

    public static <T> T read(java.util.function.Supplier<T> query) {
        try {
            return query.get();
        } catch (org.springframework.dao.DataAccessException failure) {
            throw unavailable();
        } catch (ContractProblemException failure) {
            if (failure.getStatus() == HttpStatus.SERVICE_UNAVAILABLE) {
                throw unavailable();
            }
            rethrowAuthentication(failure);
            throw failure;
        }
    }

    public static boolean hidden(RuntimeException failure) {
        return failure instanceof BusinessException b && (b.getResultCode() == ResultCode.FORBIDDEN
                || b.getResultCode() == ResultCode.MEDIA_ATTACHMENT_NOT_FOUND
                || b.getResultCode() == ResultCode.USER_NOT_FOUND)
                || failure instanceof ContractProblemException p
                && (p.getStatus() == HttpStatus.NOT_FOUND || p.getStatus() == HttpStatus.FORBIDDEN);
    }

    public static void rethrowAuthentication(RuntimeException failure) {
        if (failure instanceof cn.jualn.miniapp.common.exception.AdminAccountRestrictedException) {
            throw ContractProblemException.restrictedAdminAuthentication();
        }
        if (failure instanceof ContractProblemException problem && "ADMIN_ACCOUNT_RESTRICTED".equals(problem.getCode())) {
            throw problem;
        }
        if (failure instanceof cn.dev33.satoken.exception.NotLoginException
                || failure instanceof BusinessException b && b.getResultCode() == ResultCode.UNAUTHORIZED
                || failure instanceof ContractProblemException p && p.getStatus() == HttpStatus.UNAUTHORIZED) {
            throw new ContractProblemException(HttpStatus.UNAUTHORIZED,
                    "/problems/admin-authentication-required", "Management session required");
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Problem(String type, int status, String title) {
    }

    public static Problem problem(RuntimeException failure) {
        rethrowAuthentication(failure);
        boolean dependency = failure instanceof org.springframework.dao.DataAccessException
                || failure instanceof ContractProblemException p && p.getStatus() == HttpStatus.SERVICE_UNAVAILABLE;
        log.warn("result=failure errorCategory={} exceptionType={}",
                failure instanceof org.springframework.dao.DataAccessException ? "database" : "internal", failure.getClass().getSimpleName());
        return dependency ? new Problem("/problems/service-unavailable", 503, "Read unavailable")
                : new Problem("/problems/internal-error", 500, "Read failed");
    }
}
