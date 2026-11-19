package cn.jualn.miniapp.third.wx.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/** Mini Program wire format; never uses Official Account miniprogram/pagepath. */
public record MiniSubscribeMessageRequest(
        @JsonProperty("touser") String toUser,
        @JsonProperty("template_id") String templateId,
        String page,
        @JsonProperty("miniprogram_state") String miniProgramState,
        Map<String, DataItem> data) {
    public record DataItem(String value) { }
}
