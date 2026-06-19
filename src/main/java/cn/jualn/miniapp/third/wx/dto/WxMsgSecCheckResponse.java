package cn.jualn.miniapp.third.wx.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 小程序文本内容安全检测返回体。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class WxMsgSecCheckResponse extends WxApiResult {

    @JsonAlias("trace_id")
    private String traceId;

    private ResultInfo result;

    private List<DetailInfo> detail;

    public String getResultSuggest() {
        return result == null ? null : result.getSuggest();
    }

    public Integer getResultLabel() {
        return result == null ? null : result.getLabel();
    }

    @Data
    public static class ResultInfo {
        private String suggest;
        private Integer label;
    }

    @Data
    public static class DetailInfo {
        private String strategy;
        private Integer errcode;
        private String suggest;
        private Integer label;
        private Integer prob;
    }
}
