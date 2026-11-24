package cn.jualn.miniapp.module.officialcontent.converter;

import cn.jualn.miniapp.module.officialcontent.bo.*;
import cn.jualn.miniapp.module.officialcontent.dto.admin.OfficialContentSaveRequest;
import cn.jualn.miniapp.module.officialcontent.vo.*;
import cn.jualn.miniapp.module.media.bo.MediaAttachmentBO;
import cn.jualn.miniapp.common.exception.ContractProblemException;
import java.time.ZoneId;
import org.springframework.stereotype.Component;

@Component
public class OfficialContentConverter {
    public OfficialContentSaveBO toSaveBO(OfficialContentSaveRequest request) {
        try {
            return new OfficialContentSaveBO(request.title(), request.summary(), request.body(), request.publisher(),
                    request.category(), request.publishStatus(), request.imageAttachmentIds().stream().map(Long::valueOf).toList(),
                    request.videoAttachmentId() == null ? null : Long.valueOf(request.videoAttachmentId()));
        } catch (NumberFormatException ex) {
            throw ContractProblemException.validation(new ContractProblemException.Violation("body", "#/imageAttachmentIds", "INVALID_REFERENCE", "Invalid attachment ID"));
        }
    }
    public OfficialContentVO toVO(OfficialContentBO value, boolean detail) {
        return OfficialContentVO.builder().id(value.id().toString()).title(value.title()).summary(value.summary())
                .publisher(value.publisher()).category(value.category()).publishStatus(value.publishStatus())
                .publishedAt(value.publishedAt() == null ? null : value.publishedAt().atZone(ZoneId.of("Asia/Shanghai")).toOffsetDateTime().toString())
                .body(detail ? value.body() : null).images(detail ? value.images().stream().map(v -> media(v, "IMAGE")).toList() : null)
                .video(detail && value.video() != null ? media(value.video(), "VIDEO") : null).build();
    }
    public OfficialContentPageVO toPageVO(OfficialContentPageBO page) {
        return new OfficialContentPageVO(page.items().stream().map(v -> toVO(v, false)).toList(), page.hasMore(), page.nextCursor());
    }
    private OfficialContentVO.Media media(MediaAttachmentBO value, String kind) {
        return new OfficialContentVO.Media(value.getId().toString(), kind, value.getOriginalName(), value.getUrl());
    }
}
