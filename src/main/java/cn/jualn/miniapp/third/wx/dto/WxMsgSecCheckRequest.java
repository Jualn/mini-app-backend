package cn.jualn.miniapp.third.wx.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 小程序文本内容安全检测请求体。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WxMsgSecCheckRequest {

    private String content;

    /**
     * 接口版本，默认 2。
     */
    private Integer version;

    /**
     * 场景值：1-资料；2-评论；3-论坛；4-社交日志。
     */
    private Integer scene;

    /**
     * 用户的openid（用户需在近两小时访问过小程序）
     */
    @JsonProperty("openid")
    private String openid;
}
