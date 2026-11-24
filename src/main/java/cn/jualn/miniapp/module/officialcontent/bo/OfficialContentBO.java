package cn.jualn.miniapp.module.officialcontent.bo;

import java.time.LocalDateTime;
import java.util.List;
import cn.jualn.miniapp.module.media.bo.MediaAttachmentBO;

public record OfficialContentBO(Long id, String title, String summary, String body, String publisher,
        String category, String publishStatus, LocalDateTime publishedAt, long revision,
        List<MediaAttachmentBO> images, MediaAttachmentBO video) {}
