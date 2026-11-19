package cn.jualn.miniapp.module.audit.service;

import cn.jualn.miniapp.module.media.bo.MediaAttachmentBO;

/** Optional moderation: when enabled, publishing requires an exact resource-policy PASS. */
public interface VideoSafetyChecks {
    void reserve(long attachmentId,String objectKey,long sizeBytes,long operatorId);
    void requirePassed(MediaAttachmentBO attachment,long operatorId);
}
