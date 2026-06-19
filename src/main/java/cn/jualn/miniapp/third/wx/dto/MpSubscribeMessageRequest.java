package cn.jualn.miniapp.third.wx.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * 服务号订阅通知发送请求。
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MpSubscribeMessageRequest {

    /**
     * 接收者服务号 openid。
     */
    @JsonProperty("touser")
    private String toUser;

    /**
     * 服务号订阅通知模板 ID。
     */
    @JsonProperty("template_id")
    private String templateId;

    /**
     * 点击通知后的 H5 页面地址，可选。
     */
    private String page;

    /**
     * 跳转小程序配置，可选。
     */
    private MiniProgram miniprogram;

    /**
     * 模板字段数据。
     * key 必须与微信模板字段一致，如 thing1、time2、phrase3。
     */
    private Map<String, DataItem> data;

    @Data
    @Builder
    public static class MiniProgram {

        @JsonProperty("appid")
        private String appId;

        @JsonProperty("pagepath")
        private String pagePath;
    }

    @Data
    @Builder
    public static class DataItem {

        private String value;
    }
}