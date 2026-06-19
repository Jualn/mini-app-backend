package cn.jualn.miniapp.third.wx.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 小程序多媒体内容安全异步检测返回体。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class WxMediaCheckAsyncResponse extends WxApiResult {

    @JsonAlias("trace_id")
    private String traceId;
}
