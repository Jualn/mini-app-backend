package cn.jualn.miniapp.module.media.service;

import cn.jualn.miniapp.module.media.enums.MediaType;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.module.subject.service.SubjectExistenceService;
import cn.jualn.miniapp.module.media.bo.MediaAttachmentSimpleBO;
import cn.jualn.miniapp.module.media.bo.AttachmentTargetBO;
import cn.jualn.miniapp.module.media.converter.MediaConverter;
import cn.jualn.miniapp.module.media.bo.MediaAttachmentBO;
import cn.jualn.miniapp.module.media.entity.MediaAttachment;
import cn.jualn.miniapp.module.media.mapper.MediaAttachmentMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class MediaQueriesService implements MediaQueries {
    private static final Set<TargetType> MEDIA_SUPPORTED_TYPES = Set.of(TargetType.OFFICIAL_CONTENT,TargetType.POST,TargetType.ACTIVITY,TargetType.EXAM,TargetType.COMMENT,TargetType.USER);
    private final MediaConverter mediaConverter;
    private final MediaAttachmentMapper mediaAttachmentMapper;
    private final SubjectExistenceService targetValidator;
    private final ArticleMediaService articleMediaService;

    public List<MediaAttachmentBO> requireArticleAttachments(Map<Long,String> references) {
        return articleMediaService.requireAttachments(references);
    }

    public List<MediaAttachmentBO> listAttachments(TargetType targetType, Long targetId) {
        requireTargetId(targetId);
        assertTargetTypeAllowed(targetType);

        targetValidator.assertExists(targetType, targetId);

        List<MediaAttachment> attachments = targetType == TargetType.ACTIVITY || targetType == TargetType.EXAM
                ? mediaAttachmentMapper.selectLinked(targetType.getCode(), targetId)
                : mediaAttachmentMapper.selectList(new LambdaQueryWrapper<MediaAttachment>()
                        .eq(MediaAttachment::getTargetType, targetType.getCode())
                        .eq(MediaAttachment::getTargetId, targetId)
                        .orderByAsc(MediaAttachment::getSortOrder)
                        .orderByAsc(MediaAttachment::getId));

        return mediaConverter.toBOList(attachments);
    }

    public List<MediaAttachmentBO> listLinkedAttachments(TargetType targetType, Long targetId) {
        if (targetType != TargetType.OFFICIAL_CONTENT && targetType != TargetType.ACTIVITY && targetType != TargetType.EXAM && targetType != TargetType.ARTICLE)
            throw new BusinessException(ResultCode.MEDIA_TARGET_TYPE_UNSUPPORTED);
        return mediaConverter.toBOList(mediaAttachmentMapper.selectLinked(targetType.getCode(), targetId));
    }

    public MediaAttachmentBO getAttachment(Long attachmentId) {
        if (attachmentId == null || attachmentId <= 0) throw new BusinessException(ResultCode.MEDIA_ATTACHMENT_NOT_FOUND);
        MediaAttachment value = mediaAttachmentMapper.selectById(attachmentId);
        if (value == null || Boolean.TRUE.equals(value.getDeleted()) || ("CANDIDATE".equals(value.getRetention()) && !value.getExpiresAt().isAfter(LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"))))) throw new BusinessException(ResultCode.MEDIA_ATTACHMENT_NOT_FOUND);
        return mediaConverter.toBO(value);
    }

    public Map<Long, MediaAttachmentBO> batchGetAttachments(Collection<Long> attachmentIds) {
        if (CollectionUtils.isEmpty(attachmentIds)) {
            return Collections.emptyMap();
        }
        Map<Long, MediaAttachmentBO> result = new HashMap<>();
        for (MediaAttachmentBO attachment : mediaConverter.toBOList(
                mediaAttachmentMapper.selectBatchIds(new HashSet<>(attachmentIds)))) {
            if (!Boolean.TRUE.equals(attachment.getDeleted()))
                result.put(attachment.getId(), attachment);
        }
        return result;
    }

    public List<AttachmentTargetBO> listAttachmentTargets(Long attachmentId) {
        if (attachmentId == null || attachmentId <= 0) return List.of();
        return mediaAttachmentMapper.selectTargets(attachmentId);
    }

    public List<MediaAttachmentBO> listAttachments(MediaType mediaType, TargetType targetType, Long targetId) {
        requireTargetId(targetId);
        assertTargetTypeAllowed(targetType);

        targetValidator.assertExists(targetType, targetId);

        List<MediaAttachment> attachments = mediaAttachmentMapper.selectList(new LambdaQueryWrapper<MediaAttachment>()
                .eq(MediaAttachment::getType, mediaType.getCode())
                .eq(MediaAttachment::getTargetType, targetType.getCode())
                .eq(MediaAttachment::getTargetId, targetId)
                .orderByAsc(MediaAttachment::getSortOrder)
                .orderByAsc(MediaAttachment::getId));

        return mediaConverter.toBOList(attachments);
    }

    public List<MediaAttachmentSimpleBO> listSimpleAttachments(TargetType targetType, Long targetId) {
        requireTargetId(targetId);
        assertTargetTypeAllowed(targetType);
        targetValidator.assertExists(targetType, targetId);

        List<MediaAttachment> attachments = mediaAttachmentMapper.selectList(new LambdaQueryWrapper<MediaAttachment>()
                .select(MediaAttachment::getId, MediaAttachment::getTargetId,
                        MediaAttachment::getUrl, MediaAttachment::getSortOrder)
                .eq(MediaAttachment::getTargetType, targetType.getCode())
                .eq(MediaAttachment::getTargetId, targetId)
                .orderByAsc(MediaAttachment::getSortOrder)
                .orderByAsc(MediaAttachment::getId));

        return mediaConverter.toSimpleBOList(attachments);
    }

    public Map<Long, List<MediaAttachmentSimpleBO>> batchListSimpleAttachments(TargetType targetType, Collection<Long> targetIds) {
        if (CollectionUtils.isEmpty(targetIds)) {
            return Collections.emptyMap();
        }
        assertTargetTypeAllowed(targetType);

        List<MediaAttachmentSimpleBO> simpleAttachments =
                mediaAttachmentMapper.selectSimpleBatchByTargetIds(targetType.getCode(), targetIds);

        Map<Long, List<MediaAttachmentSimpleBO>> result = new HashMap<>();
        for (MediaAttachmentSimpleBO attachment : simpleAttachments) {
            Long targetId = attachment.getTargetId();
            result.computeIfAbsent(targetId, k -> new ArrayList<>()).add(attachment);
        }
        return result;
    }

    private void assertTargetTypeAllowed(TargetType targetType) {
        if (!MEDIA_SUPPORTED_TYPES.contains(targetType)) {
            throw new BusinessException(ResultCode.MEDIA_TARGET_TYPE_UNSUPPORTED);
        }
    }

    private void requireTargetId(Long targetId) {
        if (targetId == null) {
            throw new BusinessException(ResultCode.INVALID_OPERATION, "targetId 不能为空");
        }
    }
}
