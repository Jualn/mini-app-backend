package cn.jualn.miniapp.module.media.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateAttachmentRequest {
    @NotBlank @Pattern(regexp = "IMAGE|POSTER|QR_CODE|PDF|WORD|LINK") private String kind;
    @NotBlank @Size(max = 200) private String name;
    @NotBlank @Size(max = 512) @Pattern(regexp = "^https?://.+") private String url;
}
