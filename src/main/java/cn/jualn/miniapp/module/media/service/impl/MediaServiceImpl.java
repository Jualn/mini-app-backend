package cn.jualn.miniapp.module.media.service.impl;

import cn.jualn.miniapp.module.media.bo.ProfileMediaSnapshotBO;
import org.springframework.dao.DataIntegrityViolationException;

import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.module.media.enums.MediaType;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.exception.SystemException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.module.subject.service.SubjectExistenceService;
import cn.jualn.miniapp.module.media.bo.AttachmentItemBO;
import cn.jualn.miniapp.module.media.bo.AttachmentLinkBO;
import cn.jualn.miniapp.module.media.converter.MediaConverter;
import cn.jualn.miniapp.module.media.bo.MediaAttachmentBO;
import cn.jualn.miniapp.module.media.bo.MediaAttachmentSaveBO;
import cn.jualn.miniapp.module.media.entity.MediaAttachment;
import cn.jualn.miniapp.module.media.mapper.MediaAttachmentMapper;
import cn.jualn.miniapp.module.media.service.MediaService;
import cn.jualn.miniapp.module.media.service.MediaUploadRecordService;
import cn.jualn.miniapp.third.cos.dto.CosUploadCredentialDTO;
import cn.jualn.miniapp.third.cos.service.CosService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.*;

/** Media preparation, binding and durable deletion intent. Read facts and access policy are separate. */
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaServiceImpl implements MediaService {
    private static final Set<TargetType> MEDIA_SUPPORTED_TYPES = Set.of(TargetType.OFFICIAL_CONTENT,TargetType.POST,TargetType.ACTIVITY,TargetType.EXAM,TargetType.COMMENT,TargetType.USER);
    private final cn.jualn.miniapp.module.authorization.service.CurrentAuthorization authorization;
    private final MediaConverter mediaConverter;
    private final MediaAttachmentMapper mediaAttachmentMapper;
    private final SubjectExistenceService targetValidator;
    private final CosService cosService;
    @org.springframework.beans.factory.annotation.Autowired private cn.jualn.miniapp.module.media.service.GrowthMediaService growth;
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.beans.factory.ObjectProvider<cn.jualn.miniapp.module.media.service.MediaDeliveryUrls> deliveryUrls;
    private String deliveryUrl(String key) {return key.startsWith("private/")?deliveryUrls.getObject().forKey(key):cosService.buildPublicUrl(key);}

    private final MediaUploadRecordService uploadRecordService;
    private final cn.jualn.miniapp.module.media.service.ArticleMediaService articleMediaService;

    @Override
    public CosUploadCredentialDTO generateArticleUploadCredential(List<cn.jualn.miniapp.module.media.bo.ArticleUploadFileBO> files, long operatorId) {
        return articleMediaService.upload(files,operatorId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class,isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void replaceAttachments(MediaAttachmentSaveBO saveDTO) {
        Long userId = UserContext.getUserId();
        Long targetId = saveDTO.getTargetId();
        TargetType targetType = saveDTO.getTargetType();
        List<AttachmentItemBO> attachments = saveDTO.getAttachments() == null
                ? List.of()
                : saveDTO.getAttachments();
        long start = System.currentTimeMillis();
        log.debug("[MediaService.replaceAttachments][开始] userId={}, targetType={}, targetId={}", userId, targetType, targetId);

        requireTargetId(targetId);
        assertTargetTypeAllowed(targetType);

        targetValidator.assertExists(targetType, targetId);
        List<MediaAttachment> existingAttachments = mediaAttachmentMapper.selectList(
                new LambdaQueryWrapper<MediaAttachment>()
                        .select(MediaAttachment::getId, MediaAttachment::getType, MediaAttachment::getObjectKey, MediaAttachment::getUrl)
                        .eq(MediaAttachment::getTargetType, targetType.getCode())
                        .eq(MediaAttachment::getTargetId, targetId));
        attachments = normalizeAttachments(targetType, userId, attachments, existingAttachments);
        saveDTO.setAttachments(attachments);
        Set<String> retainedObjectKeys = attachments.stream()
                .map(AttachmentItemBO::getObjectKey)
                .filter(StringUtils::hasText)
                .collect(java.util.stream.Collectors.toSet());
        List<String> removedObjectKeys = existingAttachments.stream()
                .map(MediaAttachment::getObjectKey)
                .filter(StringUtils::hasText)
                .filter(objectKey -> !retainedObjectKeys.contains(objectKey))
                .distinct()
                .toList();
        Set<String> existingObjectKeys = existingAttachments.stream()
                .map(MediaAttachment::getObjectKey)
                .filter(StringUtils::hasText)
                .collect(java.util.stream.Collectors.toSet());
        List<String> newObjectKeys = retainedObjectKeys.stream()
                .filter(objectKey -> !existingObjectKeys.contains(objectKey))
                .toList();
        uploadRecordService.bindPending(userId, targetType, targetId, newObjectKeys);

        List<MediaAttachment> mediaAttachments = mediaConverter.toMediaAttachmentList(saveDTO);
        Set<Long> retainedIds = new HashSet<>();
        Set<String> identities = new HashSet<>();
        for (MediaAttachment incoming : mediaAttachments) {
            String identity = incoming.getType() + ":" + (StringUtils.hasText(incoming.getObjectKey())
                    ? incoming.getObjectKey() : incoming.getUrl());
            if (!identities.add(identity)) throw new BusinessException(ResultCode.INVALID_OPERATION, "同一附件不能重复添加");
            MediaAttachment existing = existingAttachments.stream()
                    .filter(old -> Objects.equals(old.getType(), incoming.getType()))
                    .filter(old -> StringUtils.hasText(incoming.getObjectKey())
                            ? Objects.equals(old.getObjectKey(), incoming.getObjectKey())
                            : Objects.equals(old.getUrl(), incoming.getUrl()))
                    .filter(old -> !retainedIds.contains(old.getId())).findFirst().orElse(null);
            if (existing == null) {
                incoming.setId(null);
                if (mediaAttachmentMapper.insert(incoming) != 1) throw new SystemException("新增附件失败");
            } else {
                incoming.setId(existing.getId());
                retainedIds.add(existing.getId());
                if (mediaAttachmentMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<MediaAttachment>()
                        .set(MediaAttachment::getUrl, incoming.getUrl())
                        .set(MediaAttachment::getObjectKey, incoming.getObjectKey())
                        .set(MediaAttachment::getOriginalName, incoming.getOriginalName())
                        .set(MediaAttachment::getSortOrder, incoming.getSortOrder())
                        .eq(MediaAttachment::getId, existing.getId())
                        .eq(MediaAttachment::getTargetType, targetType.getCode())
                        .eq(MediaAttachment::getTargetId, targetId)) != 1) throw new SystemException("更新附件失败");
            }
        }
        List<Long> removedIds = existingAttachments.stream().map(MediaAttachment::getId)
                .filter(id -> !retainedIds.contains(id)).toList();
        if (!removedIds.isEmpty()) {
            try {
                mediaAttachmentMapper.delete(new LambdaQueryWrapper<MediaAttachment>()
                        .eq(MediaAttachment::getTargetType, targetType.getCode())
                        .eq(MediaAttachment::getTargetId, targetId)
                        .in(MediaAttachment::getId, removedIds));
            } catch (DataIntegrityViolationException e) {
                throw new BusinessException(ResultCode.INVALID_OPERATION, "附件仍被参与入口引用，请同时修改对应参与入口");
            }
        }
        deleteObjectsAfterCommit(removedObjectKeys, targetType, targetId);

        log.debug("[MediaService.replaceAttachments][完成] userId={}, targetType={}, targetId={}, count={}, costMs={}",
                userId, targetType, targetId, attachments.size(), System.currentTimeMillis() - start);
    }

    @Override
    @Transactional(rollbackFor = Exception.class, isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public MediaAttachmentBO registerAttachment(String kind, String name, String url, Long operatorId) {
        throw cn.jualn.miniapp.common.exception.ContractProblemException.validation(
            new cn.jualn.miniapp.common.exception.ContractProblemException.Violation("header","Idempotency-Key","REQUIRED","Use the idempotent attachment registration operation"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class, isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void replaceAttachmentLinks(TargetType targetType, Long targetId, List<AttachmentLinkBO> links) {
        requireTargetId(targetId);
        if (targetType != TargetType.ACTIVITY && targetType != TargetType.EXAM && targetType != TargetType.OFFICIAL_CONTENT && targetType != TargetType.ARTICLE) {
            throw new BusinessException(ResultCode.MEDIA_TARGET_TYPE_UNSUPPORTED);
        }
        List<AttachmentLinkBO> values = links == null ? List.of() : links;
        if (values.size() > 100) throw new BusinessException(ResultCode.INVALID_OPERATION, "附件最多100项");
        Set<Long> ids = new HashSet<>();
        for (AttachmentLinkBO link : values) {
            if (link == null || link.attachmentId() == null || link.attachmentId() <= 0
                    || link.displayOrder() == null || link.displayOrder() < 0
                    || !ids.add(link.attachmentId())) {
                throw new BusinessException(ResultCode.INVALID_OPERATION, "附件引用或展示顺序不合法");
            }
        }
        Map<Long, MediaAttachmentBO> attachments = new HashMap<>();
        if(!ids.isEmpty()) mediaConverter.toBOList(mediaAttachmentMapper.selectBatchIds(ids)).forEach(a -> attachments.put(a.getId(),a));
        if (attachments.size() != ids.size()) throw new BusinessException(ResultCode.MEDIA_ATTACHMENT_NOT_FOUND);
        if (targetType != TargetType.ARTICLE && attachments.values().stream().anyMatch(a -> "VIDEO".equals(a.getKind()))) {
            throw new BusinessException(ResultCode.MEDIA_TARGET_TYPE_UNSUPPORTED);
        }
        var existingIds = mediaAttachmentMapper.selectLinked(targetType.getCode(), targetId).stream()
                .map(MediaAttachment::getId).collect(java.util.stream.Collectors.toSet());
        long actor = cn.jualn.miniapp.common.security.AdminStpUtil.STP_LOGIC.getLoginIdAsLong();
        for (Long id : ids.stream().sorted().toList()) {
            MediaAttachment entity = mediaAttachmentMapper.lock(id);
            if (entity == null || !Boolean.TRUE.equals(entity.getRegistered()) || Boolean.TRUE.equals(entity.getDeleted()))
                throw new BusinessException(ResultCode.MEDIA_ATTACHMENT_NOT_FOUND);
            if (!existingIds.contains(id) && !Objects.equals(entity.getRegisteredBy(), actor))
                throw new BusinessException(ResultCode.FORBIDDEN, "Only the uploader can add a new attachment reference");
            if ("CANDIDATE".equals(entity.getRetention())) {
                if (!entity.getExpiresAt().isAfter(LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"))))
                    throw cn.jualn.miniapp.common.exception.ContractProblemException.conflict("attachment-candidate-expired", "Candidate expired");
                if (!Boolean.TRUE.equals(entity.getVerified()))
                    throw cn.jualn.miniapp.common.exception.ContractProblemException.conflict("attachment-not-ready", "Candidate has not passed technical verification");
                uploadRecordService.retainCandidate(entity);
                if (mediaAttachmentMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<MediaAttachment>()
                        .set(MediaAttachment::getRetention,"FORMAL").set(MediaAttachment::getExpiresAt,null)
                        .eq(MediaAttachment::getId,id).eq(MediaAttachment::getRetention,"CANDIDATE")) != 1)
                    throw new IllegalStateException("Candidate adoption failed");
            }
        }
        mediaAttachmentMapper.deleteLinks(targetType.getCode(), targetId);
        values.stream().sorted(Comparator.comparingInt(AttachmentLinkBO::displayOrder)
                .thenComparing(AttachmentLinkBO::attachmentId))
                .forEach(link -> mediaAttachmentMapper.insertLink(targetType.getCode(), targetId,
                        link.attachmentId(), link.displayOrder()));
    }

    @Override
    public CosUploadCredentialDTO generateUploadCredential(TargetType targetType, List<String> fileNames) {
        if (targetType == TargetType.OFFICIAL_CONTENT) {
            throw new BusinessException(ResultCode.MEDIA_TARGET_TYPE_UNSUPPORTED);
        }
        return generateCredential(targetType, fileNames, requireUserId());
    }

    @Override
    public CosUploadCredentialDTO generateAdminUploadCredential(TargetType targetType, List<String> fileNames, Long operatorId) {
        if (operatorId == null || operatorId <= 0) throw new BusinessException(ResultCode.UNAUTHORIZED);
        if (targetType != TargetType.ACTIVITY && targetType != TargetType.EXAM && targetType != TargetType.OFFICIAL_CONTENT) {
            throw new BusinessException(ResultCode.MEDIA_TARGET_TYPE_UNSUPPORTED);
        }
        authorization.require(operatorId,targetType==TargetType.ACTIVITY?"activity:edit":targetType==TargetType.EXAM?"public-event:edit":"content:manage",cn.jualn.miniapp.module.authorization.service.CurrentAuthorization.namespace(targetType),"media:write");
        return generateCredential(targetType, fileNames, operatorId);
    }

    private CosUploadCredentialDTO generateCredential(TargetType targetType, List<String> fileNames, Long userId) {
        assertTargetTypeAllowed(targetType);
        if (CollectionUtils.isEmpty(fileNames)) {
            throw new BusinessException(ResultCode.INVALID_OPERATION, "fileNames 不能为空");
        }

        List<String> objectKeys = new ArrayList<>(fileNames.size());
        for (String fileName : fileNames) {
            objectKeys.add(
                    buildObjectKey(targetType, userId, fileName)
            );
        }
        CosUploadCredentialDTO credential = cosService.generateUploadCredential(objectKeys);
        LocalDateTime now = LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"));
        LocalDateTime cleanupAfter = credential.getExpireAt() == null
                || !credential.getExpireAt().isAfter(now)
                ? now.plusHours(2)
                : credential.getExpireAt().plusHours(1);
        uploadRecordService.recordPending(userId, targetType, objectKeys, cleanupAfter);
        return credential;
    }

    @Override
    public String resolveOwnedUploadUrl(TargetType targetType, String objectKey) {
        Long userId = requireUserId();
        assertTargetTypeAllowed(targetType);
        if (!StringUtils.hasText(objectKey)) {
            throw new BusinessException(ResultCode.INVALID_OPERATION, "objectKey 不能为空");
        }
        String normalizedKey = objectKey.trim();
        if((targetType==TargetType.POST || targetType==TargetType.COMMENT) && !normalizedKey.startsWith("private/"))throw new BusinessException(ResultCode.INVALID_OPERATION,"Community images require a private verified snapshot");
        String expectedPrefix = targetCategory(targetType) + "/" + userId + "/";
        if ((!normalizedKey.startsWith(expectedPrefix) && !uploadRecordService.isVerified(userId,targetType,normalizedKey)) || normalizedKey.contains("..")) {
            throw new BusinessException(ResultCode.INVALID_OPERATION, "objectKey 不属于当前用户或业务类型");
        }
        return deliveryUrl(normalizedKey);
    }

    @Override
    public List<AttachmentItemBO> prepareNewAttachments(TargetType type,List<AttachmentItemBO> items) {
        return normalizeAttachments(type,requireUserId(),items==null?List.of():items,List.of());
    }

    @Override
    public String prepareOwnedImage(TargetType type,String key) {
        return uploadRecordService.prepareVerified(requireUserId(),type,key,"IMAGE");
    }

    @Override
    public ProfileMediaSnapshotBO prepareProfileSnapshot(String objectKey) {
        String snapshotKey=prepareOwnedImage(TargetType.USER,objectKey);
        return new ProfileMediaSnapshotBO(snapshotKey,cosService.buildPublicUrl(snapshotKey));
    }

    @Override
    public void bindPendingUploads(TargetType targetType, Long targetId, Collection<String> objectKeys) {
        if (CollectionUtils.isEmpty(objectKeys)) {
            return;
        }
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new SystemException("上传记录必须在业务事务内绑定");
        }
        uploadRecordService.bindPending(requireUserId(), targetType, targetId, objectKeys);
    }

    private Long requireUserId() {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        return userId;
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

    private String buildObjectKey(TargetType type, Long userId, String fileName) {
        String safeName = sanitizeFileName(fileName);
        String category = targetCategory(type);
        String nonce = UUID.randomUUID().toString().replace("-", "");
        return ((type==TargetType.POST || type==TargetType.COMMENT)?"private/":"") + category + "/" + userId + "/" + System.currentTimeMillis() + "_" + nonce + "_" + safeName;
    }

    private String targetCategory(TargetType type) {
        return switch (type) {
            case POST -> "post";
            case ACTIVITY -> "activity";
            case EXAM -> "exam";
            case COMMENT -> "comment";
            case USER -> "user";
            case OFFICIAL_CONTENT -> "official-content";
            default -> throw new BusinessException(ResultCode.MEDIA_TARGET_TYPE_UNSUPPORTED);
        };
    }

    @Override
    public List<AttachmentItemBO> prepareAttachments(TargetType type, Long id, List<AttachmentItemBO> items) {
        requireTargetId(id); assertTargetTypeAllowed(type);
        var existing=mediaAttachmentMapper.selectList(new LambdaQueryWrapper<MediaAttachment>()
                .eq(MediaAttachment::getTargetType,type.getCode()).eq(MediaAttachment::getTargetId,id));
        if(items==null || items.stream().anyMatch(item->item==null || item.getUrl()==null || item.getUrl().isBlank()
                || item.getUrl().length()>512 || (item.getObjectKey()!=null && item.getObjectKey().length()>512)
                || (item.getOriginalName()!=null && item.getOriginalName().length()>255)
                || (item.getSortOrder()!=null && (item.getSortOrder()<0 || item.getSortOrder()>127))))
            throw new BusinessException(ResultCode.INVALID_OPERATION,"Invalid attachment declaration");
        return normalizeAttachments(type,requireUserId(),items,existing);
    }

    private List<AttachmentItemBO> normalizeAttachments(
            TargetType targetType,
            Long userId,
            List<AttachmentItemBO> attachments,
            List<MediaAttachment> existingAttachments) {
        String expectedPrefix = userId == null ? null : targetCategory(targetType) + "/" + userId + "/";
        List<AttachmentItemBO> normalized = new ArrayList<>(attachments.size());
        for (AttachmentItemBO item : attachments) {
            if (item == null || item.getType() == null) {
                throw new BusinessException(ResultCode.INVALID_OPERATION, "附件类型不能为空");
            }
            if (item.getType() == MediaType.URL) {
                if (!StringUtils.hasText(item.getUrl()) || !item.getUrl().startsWith("https://")) {
                    throw new BusinessException(ResultCode.INVALID_OPERATION, "附件外链必须使用 HTTPS");
                }
                normalized.add(AttachmentItemBO.builder()
                        .type(MediaType.URL)
                        .url(item.getUrl().trim())
                        .originalName(item.getOriginalName())
                        .sortOrder(item.getSortOrder())
                        .build());
                continue;
            }

            String objectKey = StringUtils.hasText(item.getObjectKey()) ? item.getObjectKey().trim() : null;
            if (objectKey == null) {
                objectKey = existingAttachments.stream()
                        .filter(existing -> StringUtils.hasText(existing.getObjectKey()))
                        .filter(existing -> Objects.equals(existing.getUrl(), item.getUrl()))
                        .map(MediaAttachment::getObjectKey)
                        .findFirst()
                        .orElse(null);
            }
            if (objectKey == null) {
                boolean existingLegacyUrl = existingAttachments.stream()
                        .filter(existing -> !StringUtils.hasText(existing.getObjectKey()))
                        .anyMatch(existing -> Objects.equals(existing.getUrl(), item.getUrl()));
                // MIGRATION: 仅允许继续保留目标上已存在的历史 URL，禁止新请求借 URL 绕过 objectKey 校验。
                if (!existingLegacyUrl || !cosService.isManagedPublicUrl(item.getUrl())) {
                    throw new BusinessException(ResultCode.INVALID_OPERATION, "COS 附件缺少合法 objectKey");
                }
                log.warn("[MediaService.normalizeAttachments][兼容历史URL] targetType={}, userId={}",
                        targetType, userId);
                normalized.add(item);
                continue;
            }
            if(growth.reserved(objectKey)) throw new BusinessException(ResultCode.INVALID_OPERATION,"Growth media cannot be referenced as ordinary attachments");
            final String originalKey = objectKey;
            String validatedObjectKey = objectKey;
            boolean alreadyAttachedToTarget = existingAttachments.stream()
                    .anyMatch(existing -> Objects.equals(existing.getObjectKey(), originalKey));
            boolean ownedNewUpload = (expectedPrefix != null && (validatedObjectKey.startsWith(expectedPrefix) || validatedObjectKey.startsWith("private/"+expectedPrefix))) || uploadRecordService.isVerified(userId,targetType,validatedObjectKey);
            if ((!alreadyAttachedToTarget && !ownedNewUpload) || validatedObjectKey.contains("..")) {
                throw new BusinessException(ResultCode.INVALID_OPERATION, "objectKey 不属于当前用户或业务类型");
            }

            boolean needsPrivateSnapshot=(targetType==TargetType.POST || targetType==TargetType.COMMENT) && !validatedObjectKey.startsWith("private/");
            if (!alreadyAttachedToTarget && (needsPrivateSnapshot || !uploadRecordService.isVerified(userId,targetType,validatedObjectKey))) {
                if(TransactionSynchronizationManager.isActualTransactionActive())
                    throw new BusinessException(ResultCode.INVALID_OPERATION,"Media must be verified before saving");
                validatedObjectKey=uploadRecordService.prepareVerified(userId,targetType,validatedObjectKey,
                    switch(item.getType()) {case IMAGE->"IMAGE";case PDF->"PDF";case WORD->"WORD";default->"LINK";});
            }
            if(!alreadyAttachedToTarget) uploadRecordService.requireVerifiedKind(userId,targetType,validatedObjectKey,item.getType());
            normalized.add(AttachmentItemBO.builder()
                    .type(item.getType())
                    .objectKey(validatedObjectKey)
                    .url(deliveryUrl(validatedObjectKey))
                    .originalName(item.getOriginalName())
                    .sortOrder(item.getSortOrder())
                    .build());
        }
        return normalized;
    }

    @Override
    public void deleteObjectsAfterCommit(Collection<String> objectKeys, TargetType targetType, Long targetId) {
        if (CollectionUtils.isEmpty(objectKeys)) {
            return;
        }
        List<String> keysToDelete = objectKeys.stream()
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
        keysToDelete.forEach(objectKey -> uploadRecordService.requestDeletion(objectKey, targetType, targetId));
    }

    private String sanitizeFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            throw new BusinessException(ResultCode.MEDIA_FILE_NAME_INVALID, "fileName 不能为空");
        }
        String normalized = fileName.trim().replace("\\", "/");
        int slashIndex = normalized.lastIndexOf('/');
        String onlyName = slashIndex >= 0 ? normalized.substring(slashIndex + 1) : normalized;
        if (onlyName.isBlank()) {
            throw new BusinessException(ResultCode.MEDIA_FILE_NAME_INVALID);
        }

        StringBuilder cleaned = new StringBuilder();
        for (char c : onlyName.toCharArray()) {
            if (Character.isLetterOrDigit(c) || c == '.' || c == '-' || c == '_') {
                cleaned.append(c);
            }
        }
        String result = cleaned.toString();
        if (result.isBlank()) {
            throw new BusinessException(ResultCode.MEDIA_FILE_NAME_INVALID);
        }
        return result.toLowerCase(Locale.ROOT);
    }
}
