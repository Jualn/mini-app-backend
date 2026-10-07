package cn.jualn.miniapp.common.exception;

import cn.jualn.miniapp.common.result.ResultCode;
import lombok.Getter;

/**
 * 业务异常（可预期的业务规则违反，不需要打印完整堆栈）
 */
@Getter
public class BusinessException extends RuntimeException{
    private final ResultCode resultCode;
    private final Integer code;
    private final String message;

    // 用枚举，message 用枚举默认的
    public BusinessException(ResultCode resultCode) {
        super(resultCode.getMessage());
        this.resultCode = resultCode;
        this.code    = resultCode.getCode();
        this.message = resultCode.getMessage();
    }

    // 用枚举，message 自定义覆盖
    public BusinessException(ResultCode resultCode, String message) {
        super(message);
        this.resultCode = resultCode;
        this.code    = resultCode.getCode();
        this.message = message;
    }

}
