package cn.jualn.miniapp.module.wx.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public class WxaMediaCheckMessage extends WxBaseMessage{

    /**
     * 小程序的 appid。
     */
    @JacksonXmlProperty(localName = "appid")
    private String appId;

    /**
     * 多媒体审核任务的 traceId，与提交审核时的返回值对应。
     * 支持两种字段名：trace_id（蛇形）或 traceId（驼峰）。
     */
    @JacksonXmlProperty(localName = "trace_id")
    private String traceId;

    @JacksonXmlProperty(localName = "version")
    private Integer version;

    /**
     * 详细审核结果列表，包含多个 detail 条目（如图片的多个区域或音频的多个片段）。
     * 详细检测结果。微信 XML 中可能出现一个或多个 <detail> 节点。
     */
    @JacksonXmlElementWrapper(useWrapping = false)
    @JacksonXmlProperty(localName = "detail")
    private List<Detail> detail;

    /**
     * 微信错误码，0=成功，非 0 为错误。
     */
    @JacksonXmlProperty(localName = "errcode")
    private Integer errCode;

    /**
     * 微信错误消息描述。
     */
    @JacksonXmlProperty(localName = "errmsg")
    private String errMsg;

    /**
     * 审核结果总体信息（包含 suggest 和 label）。
     */
    @JacksonXmlProperty(localName = "result")
    private Result result;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Detail {

        /**
         * 审核策略类型。
         */
        @JacksonXmlProperty(localName = "strategy")
        private String strategy;
        /**
         * 本条目的错误码。
         */
        @JacksonXmlProperty(localName = "errcode")
        private Integer errCode;
        /**
         * 本条目的审核建议：pass、risky、reject。
         */
        @JacksonXmlProperty(localName = "suggest")
        private String suggest;
        /**
         * 命中标签枚举值，100 正常；20001 时政；20002 色情；20006 违法犯罪；21000 其他
         */
        @JacksonXmlProperty(localName = "label")
        private Integer label;
        /**
         * 0-100，代表置信度，越高代表越有可能属于当前返回的标签（label）
         */
        @JacksonXmlProperty(localName = "prob")
        private Integer prob;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Result {

        /**
         * 审核建议：pass（通过）、risky（有风险）、reject（拒绝）。
         */
        @JacksonXmlProperty(localName = "suggest")
        private String suggest;

        /**
         * 命中标签枚举值，100 正常；20001 时政；20002 色情；20006 违法犯罪；21000 其他
         */
        @JacksonXmlProperty(localName = "label")
        private Integer label;
    }
}
