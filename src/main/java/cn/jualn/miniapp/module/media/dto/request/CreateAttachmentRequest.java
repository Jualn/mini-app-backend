package cn.jualn.miniapp.module.media.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateAttachmentRequest {
    @NotBlank @Pattern(regexp = "IMAGE|POSTER|QR_CODE|PDF|WORD|LINK|VIDEO") private String kind;
    @NotBlank private String name;
    @jakarta.validation.constraints.AssertTrue(message = "附件名称不能超过200个字符")
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isNameWithinLimit() { return name==null || name.codePointCount(0,name.length())<=200; }
    @NotBlank @Size(max = 512) @Pattern(regexp = "^https?://.+") private String url;
}
