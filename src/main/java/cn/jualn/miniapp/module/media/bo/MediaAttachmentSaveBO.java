package cn.jualn.miniapp.module.media.bo;

import cn.jualn.miniapp.common.enums.TargetType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 附件覆盖保存 BO（服务层/跨服务传输）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MediaAttachmentSaveBO {

    private TargetType targetType;
    private Long targetId;
    private List<AttachmentItemBO> attachments;
}