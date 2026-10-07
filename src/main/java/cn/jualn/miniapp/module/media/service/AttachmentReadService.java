package cn.jualn.miniapp.module.media.service;

import cn.jualn.miniapp.module.media.bo.MediaAttachmentBO;

/** Attachment reads combine metadata ownership with the subject owner's visibility policy. */
public interface AttachmentReadService {
    MediaAttachmentBO getPublicAttachment(Long attachmentId);

    MediaAttachmentBO getAdminAttachment(Long attachmentId);
}
