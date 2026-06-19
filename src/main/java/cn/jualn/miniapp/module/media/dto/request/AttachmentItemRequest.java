package cn.jualn.miniapp.module.media.dto.request;

import cn.jualn.miniapp.common.enums.MediaType;
import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class AttachmentItemRequest {

    /** 附件类型：1-图片 2-PDF 3-外链 4-Word。 */
    @NotNull(message = "type 不能为空")
    private MediaType type;

    /** 附件访问地址（通常为 COS URL 或外链）。 */
    @NotBlank(message = "url 不能为空")
    @Size(max = 512, message = "url 长度不能超过512")
    private String url;

    /** 原始文件名，图片和外链可为空。 */
    @Size(max = 255, message = "originalName 长度不能超过255")
    private String originalName;

    /** 展示顺序，值越小越靠前。 */
    @Min(value = 0, message = "sortOrder 不能小于0")
    @Max(value = 127, message = "sortOrder 不能大于127")
    private Integer sortOrder;
}
