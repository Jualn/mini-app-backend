package cn.jualn.miniapp.common.exception;

import lombok.Getter;

/**
 * 系统异常：不应发生的内部错误，对外不透传细节
 * 区别于 BusinessException（可预期的业务失败）
 */
@Getter
public class SystemException extends RuntimeException {

    // 保留 code 方便日志分类，但对外永远返回"服务器内部错误"
    private final int code;

    public SystemException(String internalMsg) {
        super(internalMsg);
        this.code = 500;
    }

    public SystemException(String internalMsg, Throwable cause) {
        super(internalMsg, cause);
        this.code = 500;
    }
}
