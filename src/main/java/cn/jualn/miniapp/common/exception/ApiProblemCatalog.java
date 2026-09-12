package cn.jualn.miniapp.common.exception;

import cn.jualn.miniapp.common.result.ResultCode;
import org.springframework.http.HttpStatus;

/**
 * Stable public HTTP problem semantics for internal result codes.
 *
 * <p>This is the single translation boundary between internal business codes and
 * the public API. The exhaustive switch forces each new internal code to choose
 * an HTTP status, while public problem types stay limited to concepts consumers
 * actually need to distinguish.</p>
 */
final class ApiProblemCatalog {
    private static final String PROBLEM_PREFIX = "/problems/";

    private ApiProblemCatalog() {
    }

    static Definition forResultCode(ResultCode code) {
        HttpStatus status = switch (code) {
            case BAD_REQUEST, INVALID_TARGET_TYPE, USER_ID_REQUIRED, USER_PROFILE_EMPTY,
                    POST_PARAM_INVALID, COMMENT_REPLY_INVALID, COMMENT_PARAM_INVALID,
                    ACTIVITY_PARAM_INVALID, PARAM_ERROR, WX_OAUTH_STATE_INVALID,
                    WX_JS_SDK_URL_INVALID, WX_NOTICE_PAYLOAD_INVALID, MEDIA_ATTACHMENT_EMPTY,
                    MEDIA_TARGET_TYPE_UNSUPPORTED, MEDIA_FILE_NAME_INVALID,
                    REPORT_TARGET_UNSUPPORTED, AUDIT_PARAM_INVALID, AUDIT_SCENE_UNSUPPORTED,
                    SEARCH_PARAM_INVALID, TIMELINE_PARAM_INVALID, TIMELINE_TARGET_UNSUPPORTED ->
                    HttpStatus.BAD_REQUEST;
            case UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN, USER_BANNED, USER_MUTED, AGREEMENT_NOT_SIGNED,
                    CONTENT_AUDIT_REJECT, ROLE_NOT_ENOUGH -> HttpStatus.FORBIDDEN;
            case NOT_FOUND, TARGET_NOT_FOUND, USER_NOT_FOUND, POST_NOT_FOUND,
                    COMMENT_NOT_FOUND, ACTIVITY_NOT_FOUND, EXAM_NOT_FOUND,
                    SUBSCRIBE_NOT_FOUND, NOTIFICATION_NOT_FOUND, MEDIA_ATTACHMENT_NOT_FOUND,
                    REPORT_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case METHOD_NOT_ALLOWED -> HttpStatus.METHOD_NOT_ALLOWED;
            case WX_BIND_SCENE_EXPIRED, WX_OAUTH_STATE_EXPIRED, WX_NOTICE_SUBSCRIBE_EXPIRED ->
                    HttpStatus.GONE;
            case UNSUPPORTED_MEDIA_TYPE -> HttpStatus.UNSUPPORTED_MEDIA_TYPE;
            case TOO_MANY_REQUESTS -> HttpStatus.TOO_MANY_REQUESTS;
            case INVALID_OPERATION, DATA_CONFLICT, ACTIVITY_FULL,
                    ACTIVITY_OPERATION_NOT_ALLOWED, EXAM_STATUS_INVALID, SUBSCRIBE_ALREADY,
                    WX_OPENID_NOT_BOUND, WX_NOTICE_SUBSCRIBE_INVALID,
                    REPORT_DUPLICATE, REPORT_ALREADY_HANDLED, TIMELINE_OPERATION_FAILED ->
                    HttpStatus.CONFLICT;
            case ACTIVITY_FILE_PARSE_ERROR -> HttpStatus.UNPROCESSABLE_ENTITY;
            case WX_API_ERROR, EXTERNAL_SERVICE_ERROR -> HttpStatus.BAD_GATEWAY;
            case INFRASTRUCTURE_ERROR, QUEUE_ERROR, WX_NOTICE_TEMPLATE_UNAVAILABLE ->
                    HttpStatus.SERVICE_UNAVAILABLE;
            case SERVER_ERROR, SUCCESS -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        return forStatus(status);
    }

    private static Definition forStatus(HttpStatus status) {
        return switch (status) {
            case BAD_REQUEST -> definition(status, "invalid-request", "Invalid request");
            case UNAUTHORIZED -> definition(status, "unauthorized", "Authentication required");
            case FORBIDDEN -> definition(status, "forbidden", "Access forbidden");
            case NOT_FOUND -> definition(status, "resource-not-found", "Resource not found");
            case METHOD_NOT_ALLOWED -> definition(status, "method-not-allowed", "Method not allowed");
            case CONFLICT -> definition(status, "conflict", "Conflict");
            case GONE -> definition(status, "resource-gone", "Resource no longer available");
            case UNSUPPORTED_MEDIA_TYPE -> definition(status, "unsupported-media-type", "Unsupported media type");
            case UNPROCESSABLE_ENTITY -> definition(status, "unprocessable-content", "Unprocessable content");
            case TOO_MANY_REQUESTS -> definition(status, "too-many-requests", "Too many requests");
            case BAD_GATEWAY -> definition(status, "external-service-error", "External service unavailable");
            case SERVICE_UNAVAILABLE -> definition(status, "service-unavailable", "Service unavailable");
            case INTERNAL_SERVER_ERROR -> definition(status, "internal-error", "Internal server error");
            default -> throw new IllegalArgumentException("Unsupported problem status: " + status);
        };
    }

    private static Definition definition(HttpStatus status, String typeSlug, String title) {
        return new Definition(status, PROBLEM_PREFIX + typeSlug, title);
    }

    record Definition(HttpStatus status, String type, String title) {
    }
}
