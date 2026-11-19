package cn.jualn.miniapp.module.media.service;

import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.module.media.bo.*;
import cn.jualn.miniapp.module.media.enums.MediaType;
import cn.jualn.miniapp.third.cos.dto.CosUploadCredentialDTO;
import java.util.*;

/** Metadata facts for callers that already own subject authorization. */
public interface MediaQueries {
    List<MediaAttachmentBO> listAttachments(TargetType targetType, Long targetId);
    List<MediaAttachmentBO> listLinkedAttachments(TargetType targetType, Long targetId);
    MediaAttachmentBO getAttachment(Long attachmentId);
    Map<Long, MediaAttachmentBO> batchGetAttachments(Collection<Long> attachmentIds);
    List<MediaAttachmentBO> requireArticleAttachments(Map<Long,String> references);
    List<AttachmentTargetBO> listAttachmentTargets(Long attachmentId);
    List<MediaAttachmentBO> listAttachments(MediaType mediaType, TargetType targetType, Long targetId);
    List<MediaAttachmentSimpleBO> listSimpleAttachments(TargetType targetType, Long targetId);
    Map<Long, List<MediaAttachmentSimpleBO>> batchListSimpleAttachments(TargetType targetType, Collection<Long> targetIds);
}
