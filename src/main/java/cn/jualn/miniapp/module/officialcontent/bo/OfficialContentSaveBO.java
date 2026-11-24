package cn.jualn.miniapp.module.officialcontent.bo;

import java.util.List;
import cn.jualn.miniapp.module.officialcontent.enums.*;

public record OfficialContentSaveBO(String title, String summary, String body, String publisher,
        OfficialContentCategory category, OfficialContentStatus publishStatus,
        List<Long> imageAttachmentIds, Long videoAttachmentId) {
    public OfficialContentSaveBO { imageAttachmentIds = List.copyOf(imageAttachmentIds); }
}
