package cn.jualn.miniapp.common.exception;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import cn.jualn.miniapp.common.result.ResultCode;
import jakarta.validation.ConstraintViolationException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.MethodParameter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.reactive.function.client.WebClientException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.net.URI;
import java.util.List;
import java.util.Objects;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(cn.jualn.miniapp.infrastructure.cache.AdminQrLoginStore.RateLimited.class)
    public ResponseEntity<ProblemDetail> handleQrRate(cn.jualn.miniapp.infrastructure.cache.AdminQrLoginStore.RateLimited exception) {
        ResponseEntity<ProblemDetail> response = problem(HttpStatus.TOO_MANY_REQUESTS, "/problems/rate-limited",
                "Rate limited", "Please wait before retrying");
        return ResponseEntity.status(response.getStatusCode()).headers(response.getHeaders())
                .header("Retry-After", Long.toString(exception.retryAfter())).body(response.getBody());
    }
    @ExceptionHandler(ContractProblemException.class)
    public ResponseEntity<ProblemDetail> handleContractProblem(ContractProblemException exception) {
        ResponseEntity<ProblemDetail> response = problem(exception.getStatus(), exception.getType(),
                exception.getStatus().getReasonPhrase(), exception.getMessage());
        if (!exception.getErrors().isEmpty()) response.getBody().setProperty("errors", exception.getErrors());
        if ("/problems/notification-preferences-unavailable".equals(exception.getType())) {
            return ResponseEntity.status(response.getStatusCode())
                    .headers(response.getHeaders())
                    .header(org.springframework.http.HttpHeaders.CACHE_CONTROL, "no-store")
                    .body(response.getBody());
        }
        return response;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException exception) {
        List<ValidationProblem> errors = bindingErrors("body", exception);
        return validationProblem(errors);
    }

    @ExceptionHandler(BindException.class)
    public ResponseEntity<ProblemDetail> handleBind(BindException exception) {
        List<ValidationProblem> errors = bindingErrors("query", exception);
        return validationProblem(errors);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ProblemDetail> handleMethodValidation(HandlerMethodValidationException exception) {
        List<ValidationProblem> errors = exception.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream().map(error -> new ValidationProblem(
                        parameterLocation(result.getMethodParameter()),
                        Objects.requireNonNullElse(result.getMethodParameter().getParameterName(), "parameter"),
                        validationCode(firstCode(error.getCodes())),
                        Objects.requireNonNullElse(error.getDefaultMessage(), "参数错误"))))
                .toList();
        return validationProblem(errors);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> handleConstraintViolation(ConstraintViolationException exception) {
        List<ValidationProblem> errors = exception.getConstraintViolations().stream()
                .map(violation -> new ValidationProblem(
                        "query",
                        violation.getPropertyPath().toString(),
                        validationCode(violation.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName()),
                        violation.getMessage()))
                .toList();
        return validationProblem(errors);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ProblemDetail> handleTypeMismatch(MethodArgumentTypeMismatchException exception) {
        return validationProblem(List.of(new ValidationProblem(
                "query", exception.getName(), "TYPE_MISMATCH", "参数类型错误")));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ProblemDetail> handleMissingParam(MissingServletRequestParameterException exception) {
        return validationProblem(List.of(new ValidationProblem(
                "query", exception.getParameterName(), "REQUIRED", "缺少必要参数")));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleNotReadable(HttpMessageNotReadableException exception) {
        return validationProblem(List.of(new ValidationProblem(
                "body", "/", "MALFORMED", "请求体格式错误")));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ProblemDetail> handleMaxUploadSize(
            MaxUploadSizeExceededException exception, HttpServletRequest request) {
        if ("/v1/admin/document-imports".equals(request.getRequestURI())) {
            return problem(HttpStatus.PAYLOAD_TOO_LARGE, "/problems/document-import-too-large",
                    "Payload too large", "导入文件或 multipart 请求大小超限");
        }
        return problem(HttpStatus.PAYLOAD_TOO_LARGE, "/problems/payload-too-large",
                "Payload too large", "文件大小超限");
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ProblemDetail> handleMissingPart(MissingServletRequestPartException exception) {
        return validationProblem(List.of(new ValidationProblem(
                "body", exception.getRequestPartName(), "REQUIRED", "缺少必要字段")));
    }

    @ExceptionHandler(NotLoginException.class)
    public ResponseEntity<ProblemDetail> handleNotLogin(NotLoginException exception) {
        return problem(HttpStatus.UNAUTHORIZED, "/problems/unauthorized",
                "Authentication required", "缺少有效的用户身份凭证");
    }

    @ExceptionHandler(NotPermissionException.class)
    public ResponseEntity<ProblemDetail> handleNotPermission(NotPermissionException exception) {
        return forbidden("无权限访问");
    }

    @ExceptionHandler(NotRoleException.class)
    public ResponseEntity<ProblemDetail> handleNotRole(NotRoleException exception) {
        return forbidden("角色权限不足");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ProblemDetail> handleMethodNotSupported(HttpRequestMethodNotSupportedException exception) {
        return problem(HttpStatus.METHOD_NOT_ALLOWED, "/problems/method-not-allowed",
                "Method not allowed", "当前资源不支持该请求方法");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ProblemDetail> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException exception) {
        return problem(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "/problems/unsupported-media-type",
                "Unsupported media type", "不支持的媒体类型");
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<ProblemDetail> handleMediaTypeNotAcceptable(
            HttpMediaTypeNotAcceptableException exception) {
        return problem(HttpStatus.NOT_ACCEPTABLE, "about:blank",
                "Not acceptable", "请求的响应媒体类型不可用");
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ProblemDetail> handleBusiness(BusinessException exception) {
        ResultCode resultCode = exception.getResultCode();
        ApiProblemCatalog.Definition definition = ApiProblemCatalog.forResultCode(resultCode);
        return problem(definition.status(), definition.type(), definition.title(), exception.getMessage());
    }

    @ExceptionHandler(SystemException.class)
    public ResponseEntity<ProblemDetail> handleSystem(SystemException exception) {
        log.error("result=failure errorCategory=internal exceptionType={}",
                exception.getClass().getSimpleName(), exception);
        return internalError();
    }

    @ExceptionHandler(ExternalServiceException.class)
    public ResponseEntity<ProblemDetail> handleExternalService(ExternalServiceException exception) {
        ApiProblemCatalog.Definition definition = ApiProblemCatalog.forResultCode(exception.getResultCode());
        log.error("result=failure errorCategory=remote provider={} code={} providerCode={} exceptionType={}",
                exception.getProvider(), exception.getCode(), safeProviderCode(exception.getProviderCode()),
                exception.getClass().getSimpleName());
        return problem(definition.status(), definition.type(),
                definition.title(), exception.getClientMessage());
    }

    @ExceptionHandler(WebClientException.class)
    public ResponseEntity<ProblemDetail> handleWebClient(WebClientException exception) {
        log.error("result=failure errorCategory=remote exceptionType={}",
                exception.getClass().getSimpleName());
        return problem(HttpStatus.BAD_GATEWAY, "/problems/external-service-error",
                "External service unavailable", "外部服务暂不可用，请稍后再试");
    }

    @ExceptionHandler({NoResourceFoundException.class, org.springframework.web.servlet.NoHandlerFoundException.class})
    public ResponseEntity<ProblemDetail> handleNoResourceFound(Exception exception) {
        return problem(HttpStatus.NOT_FOUND, "/problems/resource-not-found",
                "Resource not found", "资源不存在");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleDataIntegrity(DataIntegrityViolationException exception) {
        return problem(HttpStatus.CONFLICT, "/problems/data-conflict",
                "Data conflict", "数据状态冲突");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleException(Exception exception) {
        log.error("result=failure errorCategory=internal exceptionType={}",
                exception.getClass().getSimpleName(), exception);
        return internalError();
    }

    private String safeProviderCode(String code) {
        return code != null && code.matches("[A-Za-z0-9_.-]{1,64}") ? code : "unknown";
    }

    private ResponseEntity<ProblemDetail> validationProblem(List<ValidationProblem> errors) {
        ProblemDetail body = createProblem(HttpStatus.BAD_REQUEST, "/problems/validation-error",
                "Request validation failed", firstDetail(errors));
        body.setProperty("errors", errors);
        return response(body, HttpStatus.BAD_REQUEST);
    }

    private ResponseEntity<ProblemDetail> forbidden(String detail) {
        return problem(HttpStatus.FORBIDDEN, "/problems/forbidden", "Access forbidden", detail);
    }

    private ResponseEntity<ProblemDetail> internalError() {
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "/problems/internal-error",
                "Internal server error", "服务器内部错误");
    }

    private ResponseEntity<ProblemDetail> problem(
            HttpStatus status, String type, String title, String detail) {
        return response(createProblem(status, type, title, detail), status);
    }

    private ProblemDetail createProblem(HttpStatus status, String type, String title, String detail) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setType(URI.create(type));
        body.setTitle(title);
        String traceId = MDC.get("traceId");
        if (traceId != null) {
            body.setProperty("traceId", traceId);
        }
        return body;
    }

    private ResponseEntity<ProblemDetail> response(ProblemDetail body, HttpStatus status) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(body);
    }

    private List<ValidationProblem> bindingErrors(String location, BindException exception) {
        return exception.getBindingResult().getAllErrors().stream()
                .map(error -> toValidationProblem(location, error))
                .toList();
    }

    private ValidationProblem toValidationProblem(String location, ObjectError error) {
        String pointer = error instanceof FieldError fieldError
                ? fieldPointer(location, fieldError.getField())
                : "/";
        return new ValidationProblem(
                location,
                pointer,
                validationCode(error.getCode()),
                Objects.requireNonNullElse(error.getDefaultMessage(), "参数错误"));
    }

    private String fieldPointer(String location, String field) {
        if (!"body".equals(location)) {
            return field;
        }
        return "/" + field.replace("~", "~0").replace("/", "~1").replace('.', '/');
    }

    private String parameterLocation(MethodParameter parameter) {
        if (parameter.hasParameterAnnotation(PathVariable.class)) {
            return "path";
        }
        if (parameter.hasParameterAnnotation(RequestHeader.class)) {
            return "header";
        }
        if (parameter.hasParameterAnnotation(RequestParam.class)) {
            return "query";
        }
        return "request";
    }

    private String validationCode(String code) {
        if (code == null || code.isBlank()) {
            return "INVALID";
        }
        return switch (code) {
            case "NotNull", "NotBlank", "NotEmpty" -> "REQUIRED";
            case "Size" -> "SIZE";
            case "Min", "DecimalMin", "Positive", "PositiveOrZero" -> "MINIMUM";
            case "Max", "DecimalMax", "Negative", "NegativeOrZero" -> "MAXIMUM";
            case "Pattern", "Email" -> "FORMAT";
            default -> "INVALID";
        };
    }

    private String firstCode(String[] codes) {
        return codes == null || codes.length == 0 ? null : codes[0];
    }

    private String firstDetail(List<ValidationProblem> errors) {
        return errors.isEmpty() ? "请求参数错误" : errors.get(0).detail();
    }

    public record ValidationProblem(String in, String pointer, String code, String detail) {
    }
}
