package cn.jualn.miniapp.common.result;

import lombok.Data;
import org.slf4j.MDC;

/**
 * 统一响应体
 */
@Data
public class Result<T> {
    private Integer code;
    private String  message;
    private T       data;
    private Long    timestamp;
    private String  traceId;

    public static <T> Result<T> ok(T data) {
        Result<T> r = new Result<>();
        r.code      = 200;
        r.message   = "success";
        r.data      = data;
        r.timestamp = System.currentTimeMillis();
        r.traceId   = MDC.get("traceId");
        return r;
    }

    public static <T> Result<T> fail(ResultCode resultCode) {
        Result<T> r = new Result<>();
        r.code      = resultCode.getCode();
        r.message   = resultCode.getMessage();
        r.timestamp = System.currentTimeMillis();
        r.traceId   = MDC.get("traceId");
        return r;
    }

    public static <T> Result<T> fail(ResultCode resultCode, String message) {
        Result<T> r = new Result<>();
        r.code      = resultCode.getCode();
        r.message   = message;           // 用自定义 message 覆盖
        r.timestamp = System.currentTimeMillis();
        r.traceId   = MDC.get("traceId");
        return r;
    }

    public static <T> Result<T> fail(Integer code, String message) {
        Result<T> r = new Result<>();
        r.code      = code;
        r.message   = message;
        r.timestamp = System.currentTimeMillis();
        r.traceId   = MDC.get("traceId");
        return r;
    }
}
