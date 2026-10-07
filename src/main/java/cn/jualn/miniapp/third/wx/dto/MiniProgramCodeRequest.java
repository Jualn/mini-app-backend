package cn.jualn.miniapp.third.wx.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record MiniProgramCodeRequest(String page, String scene,
        @JsonProperty("env_version") String envVersion,
        @JsonProperty("check_path") boolean checkPath, int width,
        @JsonProperty("is_hyaline") boolean isHyaline) {
    @Override public String toString() { return "MiniProgramCodeRequest[scene redacted]"; }
}
