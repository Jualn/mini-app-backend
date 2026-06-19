package cn.jualn.miniapp.third.wx.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 服务号模板消息请求体。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MpTemplateMessageRequest {

    /**
     * 接收用户在服务号的 openid
     */
    @JsonProperty("touser")
    private String toUser;

    @JsonProperty("template_id")
    private String templateId;

    private String url;

    @JsonProperty("miniprogram")
    private Map<String, String> miniProgram;

    /**
     * 键为模板参数名（如 first、keyword1）。
     */
    private Map<String, Map<String, String>> data;
}

