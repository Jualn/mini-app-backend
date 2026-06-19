package cn.jualn.miniapp.module.media.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 附件覆盖保存请求。
 *
 * <p>用于替换指定目标下的全部附件。</p>
 */
@Data
public class MediaAttachmentSaveRequest {

	/** 目标类型：1-帖子 2-活动 3-考试信息。 */
    @NotNull(message = "targetType 不能为空")
    private Integer targetType;

	/** 目标 ID。 */
    @NotNull(message = "targetId 不能为空")
    private Long targetId;

	/** 附件列表，最多 9 条。 */
    @Valid
    @NotNull(message = "attachments 不能为空")
    @Size(max = 9, message = "附件数量不能超过9")
    private List<AttachmentItem> attachments;

	/**
	 * 单条附件项。
	 */
    @Data
    public static class AttachmentItem {

		/** 附件类型：1-图片 2-PDF 3-外链 4-Word。 */
        @NotNull(message = "type 不能为空")
        @Min(value = 1, message = "type 不合法")
        @Max(value = 4, message = "type 不合法")
        private Integer type;

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
}
