package cn.jualn.miniapp.module.media.service;

import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.module.media.bo.*;
import cn.jualn.miniapp.module.media.enums.MediaType;
import cn.jualn.miniapp.third.cos.dto.CosUploadCredentialDTO;
import java.util.*;

/** Access decisions for reusable and administrative media representations. */
public interface MediaAccess {
    List<AdminAttachmentReadBO> readAdminAttachments(List<Long> ids, long actor);
    void authorizeReusableRead(Long attachmentId,long actor);
    void authorizeArticleMedia();
}
