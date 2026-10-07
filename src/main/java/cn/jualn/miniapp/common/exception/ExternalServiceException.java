package cn.jualn.miniapp.common.exception;

import cn.jualn.miniapp.common.result.ResultCode;
import lombok.Getter;

/**
 * 外部服务异常。
 *
 * <p>用于微信、COS、AI 等第三方调用失败。内部 message 可包含供应商返回细节，
 * 对外只返回稳定的 ResultCode 文案，避免把 access_token、errcode 等细节透给前端。</p>
 */
@Getter
public class ExternalServiceException extends RuntimeException {

    private final ResultCode resultCode;
    private final String provider;
    private final String providerCode;

    public ExternalServiceException(ResultCode resultCode, String provider, String internalMsg) {
        super(internalMsg);
        this.resultCode = resultCode;
        this.provider = provider;
        this.providerCode = null;
    }

    public ExternalServiceException(ResultCode resultCode, String provider, String internalMsg, Throwable cause) {
        super(internalMsg, cause);
        this.resultCode = resultCode;
        this.provider = provider;
        this.providerCode = null;
    }

    public ExternalServiceException(ResultCode resultCode, String provider, String providerCode,
                                    String internalMsg) {
        super(internalMsg);
        this.resultCode = resultCode;
        this.provider = provider;
        this.providerCode = providerCode;
    }

    public Integer getCode() {
        return resultCode.getCode();
    }

    public String getClientMessage() {
        return resultCode.getMessage();
    }
}
