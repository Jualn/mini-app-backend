package cn.jualn.miniapp.module.media.converter;

import cn.jualn.miniapp.common.web.AdminQuery;
import cn.jualn.miniapp.module.media.bo.AdminAttachmentReadBO;
import cn.jualn.miniapp.module.media.vo.AdminAttachmentQueryVO;

import cn.jualn.miniapp.module.media.bo.MediaAttachmentBO;
import cn.jualn.miniapp.module.media.vo.AttachmentVO;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class AttachmentConverter {
    public AdminAttachmentQueryVO.Item toReadVO(
            AdminAttachmentReadBO value) {
        try {
            return new AdminAttachmentQueryVO.Item(value.attachmentId().toString(), value.status(),
                    value.attachment() == null ? null : toVO(value.attachment()), value.problem());
        } catch (RuntimeException failure) {
            return new AdminAttachmentQueryVO.Item(value.attachmentId().toString(), "ERROR", null,
                    AdminQuery.problem(failure));
        }
    }
    public AttachmentVO toVO(MediaAttachmentBO value) {
        boolean articleDocument=("PDF".equals(value.getKind()) || "WORD".equals(value.getKind()))
                && value.getObjectKey()!=null && value.getObjectKey().startsWith("article-resource/");
        return new AttachmentVO(value.getId().toString(), value.getKind() != null ? value.getKind() :
                switch (Objects.requireNonNull(value.getType(), "attachment type")) {
                    case IMAGE -> "IMAGE";
                    case PDF -> "PDF";
                    case WORD -> "WORD";
                    case URL -> "LINK";
                }, value.getOriginalName() == null ? "attachment-" + value.getId() : value.getOriginalName(),
                value.getUrl(), "VIDEO".equals(value.getKind()) ? new AttachmentVO.Playback(
                        value.getContentType(), value.getWidth(), value.getHeight(), value.getDurationSeconds()) : null,
                value.getRetention() == null ? "FORMAL" : value.getRetention(),
                "CANDIDATE".equals(value.getRetention()) && value.getExpiresAt()!=null
                    ? value.getExpiresAt().atZone(java.time.ZoneId.of("Asia/Shanghai")).toOffsetDateTime() : null,
                articleDocument?value.getContentType():null,articleDocument?value.getSizeBytes():null);
    }
}
