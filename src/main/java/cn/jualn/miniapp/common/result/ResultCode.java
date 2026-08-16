package cn.jualn.miniapp.common.result;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 业务错误码枚举
 * 规则：HTTP协议码 | 1xxx-系统/基础设施 | 2xxx-用户 | 3xxx-内容 | 4xxx-活动 | 5xxx-考试
 *      | 6xxx-通知/订阅 | 7xxx-微信业务 | 8xxx-附件 | 9xxx-举报 | 11xxx-审核 | 12xxx-搜索 | 13xxx-时间线
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
    INVALID_OPERATION(1005, "操作不合法"),
    DATA_CONFLICT(1006, "数据状态冲突"),
    EXTERNAL_SERVICE_ERROR(1007, "外部服务暂不可用，请稍后再试"),
    INFRASTRUCTURE_ERROR(1008, "系统服务暂不可用，请稍后再试"),
    QUEUE_ERROR(1009, "异步任务提交失败，请稍后再试"),

    // 用户模块 2-
    USER_BANNED(2000, "账号已被封禁"),
    USER_MUTED(2001, "账号已被禁言"),
    AGREEMENT_NOT_SIGNED(2002, "请先同意用户协议"),
    CONTENT_AUDIT_REJECT(2003, "内容未通过审核"),
    ROLE_NOT_ENOUGH(2004, "权限不足"),
    USER_NOT_FOUND(2005, "用户不存在"),
    USER_ID_REQUIRED(2006, "用户ID不能为空"),
    USER_PROFILE_EMPTY(2007, "没有可更新的资料字段"),


    // 内容相关 3-
    POST_NOT_FOUND(3000, "帖子不存在"),
    POST_PARAM_INVALID(3001, "帖子参数不合法"),
    COMMENT_NOT_FOUND(3100, "评论不存在"),
    COMMENT_REPLY_INVALID(3101, "回复不合法"),
    COMMENT_PARAM_INVALID(3102, "评论参数不合法"),


    // 活动相关 4-
    ACTIVITY_FULL(4000, "活动人数已满"),
    ACTIVITY_FILE_PARSE_ERROR(4001, "活动文件解析失败"),
    ACTIVITY_NOT_FOUND(4002, "活动不存在"),
    ACTIVITY_OPERATION_NOT_ALLOWED(4003, "活动状态不允许此操作"),
    ACTIVITY_PARAM_INVALID(4004, "活动参数不合法"),

    // 考试相关 5-
    EXAM_NOT_FOUND(5000, "考试信息不存在"),
    EXAM_STATUS_INVALID(5001, "考试状态不允许此操作"),


    // 通知或订阅 6-
    SUBSCRIBE_ALREADY(6000, "已订阅，请勿重复操作"),
    SUBSCRIBE_NOT_FOUND(6001, "订阅记录不存在"),
    NOTIFICATION_NOT_FOUND(6002, "通知不存在"),

    // 微信相关 7-
    PARAM_ERROR(7000, "参数错误"),
    WX_OAUTH_STATE_EXPIRED(7001, "微信授权状态已过期，请重新打开页面"),
    WX_OAUTH_STATE_INVALID(7002, "微信授权状态异常，请重新打开页面"),
    WX_NOTICE_TEMPLATE_UNAVAILABLE(7003, "服务号通知模板不可用"),
    WX_OPENID_NOT_BOUND(7004, "用户未绑定服务号"),
    WX_NOTICE_SUBSCRIBE_INVALID(7005, "服务号通知订阅状态异常，请重新开启"),
    WX_NOTICE_SUBSCRIBE_EXPIRED(7006, "服务号通知订阅状态已过期，请重新开启"),
    WX_JS_SDK_URL_INVALID(7007, "微信 JS-SDK URL 不合法"),
    WX_NOTICE_PAYLOAD_INVALID(7008, "微信通知数据不合法"),

    // 附件相关 8-
    MEDIA_ATTACHMENT_EMPTY(8000, "附件不能为空"),
    MEDIA_ATTACHMENT_NOT_FOUND(8001, "附件不存在"),
    MEDIA_TARGET_TYPE_UNSUPPORTED(8002, "附件目标类型不支持"),
    MEDIA_FILE_NAME_INVALID(8003, "文件名不合法"),

    // 举报相关 9-
    REPORT_DUPLICATE(9000, "请勿重复举报同一内容"),
    REPORT_NOT_FOUND(9001, "举报记录不存在"),
    REPORT_ALREADY_HANDLED(9002, "举报已处理"),
    REPORT_TARGET_UNSUPPORTED(9003, "不支持的举报对象类型"),

    // 审核相关 11-
    AUDIT_PARAM_INVALID(11000, "审核参数不合法"),
    AUDIT_SCENE_UNSUPPORTED(11001, "不支持的审核场景"),

    // 搜索相关 12-
    SEARCH_PARAM_INVALID(12000, "搜索参数不合法"),

    // 时间线相关 13-
    TIMELINE_PARAM_INVALID(13000, "时间线参数不合法"),
    TIMELINE_OPERATION_FAILED(13001, "时间线操作失败"),
    TIMELINE_TARGET_UNSUPPORTED(13002, "不支持的时间线目标类型")
    ;


    private final int code;
    private final String message;
}
