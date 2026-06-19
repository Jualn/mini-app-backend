package cn.jualn.miniapp.common.result;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 业务错误码枚举
 * 规则：通用 | 1xx-系统 | 2xx-用户 | 3xx-帖子 | 4xx-活动 | 5xx-考试 | 6xx-通知/订阅
 */
@Getter
@AllArgsConstructor
public enum ResultCode {
    // ========== 通用成功 ==========
    SUCCESS(200, "success"),

    // ========== HTTP 协议层（只给 GlobalExceptionHandler 用）==========
    BAD_REQUEST(400, "请求参数错误"),
    UNAUTHORIZED(401, "未登录或Token已过期"),
    FORBIDDEN(403, "无权限访问"),
    NOT_FOUND(404, "资源不存在"),
    METHOD_NOT_ALLOWED(405, "请求方式不支持"),
    UNSUPPORTED_MEDIA_TYPE(415, "不支持的媒体类型"),
    SERVER_ERROR(500, "服务器内部错误"),

    // ========== 业务异常（业务代码主动抛出）==========
    // 系统模块  100x
    WX_API_ERROR(1000, "微信接口调用失败"),
    WX_BIND_SCENE_EXPIRED(1001, "微信绑定场景已失效"),
    TARGET_NOT_FOUND(1002,"目标资源不存在"),
    INVALID_TARGET_TYPE(1003,"无效的目标类型"),
    TOO_MANY_REQUESTS(1004, "请求过于频繁，请稍后再试"),

    // 用户模块 2-
    USER_BANNED(2000, "账号已被封禁"),
    USER_MUTED(2001, "账号已被禁言"),
    AGREEMENT_NOT_SIGNED(2002, "请先同意用户协议"),
    CONTENT_AUDIT_REJECT(2003, "内容未通过审核"),
    ROLE_NOT_ENOUGH(2004, "权限不足"),
    USER_NOT_FOUND(2005, "用户不存在"),


    // 帖子相关 3-


    // 活动相关 4-
    ACTIVITY_FULL(4000, "活动人数已满"),
    ACTIVITY_FILE_PARSE_ERROR(4001, "活动文件解析失败"),

    // 考试相关 5-
    EXAM_NOT_FOUND(5000, "考试信息不存在"),
    EXAM_STATUS_INVALID(5001, "考试状态不允许此操作"),


    // 通知或订阅 6-
    SUBSCRIBE_ALREADY(6000, "已订阅，请勿重复操作"),
    SUBSCRIBE_NOT_FOUND(6001, "订阅记录不存在"),

    // 微信相关 7-
    PARAM_ERROR(7000, "参数错误")
    ;


    private final int code;
    private final String message;
}
