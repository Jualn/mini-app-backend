package cn.jualn.miniapp.module.media.service.impl;

import cn.jualn.miniapp.module.media.bo.ProfileMediaSnapshotBO;
import org.springframework.dao.DataIntegrityViolationException;

import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.common.enums.MediaType;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.exception.SystemException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.infrastructure.validator.TargetValidator;
import cn.jualn.miniapp.module.media.bo.MediaAttachmentSimpleBO;
import cn.jualn.miniapp.module.media.bo.AttachmentItemBO;
import cn.jualn.miniapp.module.media.bo.AttachmentLinkBO;
import cn.jualn.miniapp.module.media.bo.AttachmentTargetBO;
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

/**
 * 通用附件服务实现。
 *
 * <p>核心职责：</p>
 * <ul>
 *   <li>管理帖子/活动/考试的附件（覆盖保存、查询、删除）；</li>
 *   <li>校验目标类型与目标存在性；</li>
 *   <li>生成前端直传 COS 所需的 STS 上传凭证。</li>
 * </ul>
 *
 * <p>目标存在性复用 TargetValidator，由基础设施统一管理缓存与 DB 回源。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaServiceImpl implements MediaService {

    private static final Set<TargetType> MEDIA_SUPPORTED_TYPES =
            Set.of(TargetType.POST, TargetType.ACTIVITY, TargetType.EXAM
                    , TargetType.COMMENT, TargetType.USER);

    private final MediaConverter mediaConverter;
    private final MediaAttachmentMapper mediaAttachmentMapper;
    private final TargetValidator targetValidator;
    private final CosService cosService;
    private final MediaUploadRecordService uploadRecordService;

    /**
     * 覆盖保存目标附件。
     *
     * <p>写入语义：按资源身份保留原ID，差量增删改，保持数据与前端提交结果一致。</p>
     *
     * @param saveDTO <p>目标类型：{@link TargetType}</p>
     *                <p>目标 ID</p>
     *                <p>附件列表</p>
     * @throws BusinessException 参数非法、目标不存在或新增对象不属于当前上传者
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
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

    /**
     * 查询目标附件列表。
     *
     * @param targetType 目标类型
     * @param targetId   目标 ID
     * @return 按 sortOrder、id 升序排列的附件视图列表
     * @throws BusinessException 参数非法或目标不存在
     */
    @Override
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

    @Override
    public MediaAttachmentBO getAttachment(Long attachmentId) {
        if (attachmentId == null || attachmentId <= 0) throw new BusinessException(ResultCode.MEDIA_ATTACHMENT_NOT_FOUND);
        MediaAttachment value = mediaAttachmentMapper.selectById(attachmentId);
        if (value == null) throw new BusinessException(ResultCode.MEDIA_ATTACHMENT_NOT_FOUND);
        return mediaConverter.toBO(value);
    }

    @Override
    public Map<Long, MediaAttachmentBO> batchGetAttachments(Collection<Long> attachmentIds) {
        if (CollectionUtils.isEmpty(attachmentIds)) {
            return Collections.emptyMap();
        }
        Map<Long, MediaAttachmentBO> result = new HashMap<>();
        for (MediaAttachmentBO attachment : mediaConverter.toBOList(
                mediaAttachmentMapper.selectBatchIds(new HashSet<>(attachmentIds)))) {
            result.put(attachment.getId(), attachment);
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MediaAttachmentBO registerAttachment(String kind, String name, String url, Long operatorId) {
        if (operatorId == null || !Set.of("IMAGE", "POSTER", "QR_CODE", "PDF", "WORD", "LINK").contains(kind)) {
            throw new BusinessException(ResultCode.INVALID_OPERATION, "附件类型不合法");
        }
        if (!StringUtils.hasText(name) || name.trim().length() > 200) {
            throw new BusinessException(ResultCode.INVALID_OPERATION, "附件名称为空或超过200字");
        }
        if (!StringUtils.hasText(url) || !url.trim().matches("^https?://.+")) {
            throw new BusinessException(ResultCode.INVALID_OPERATION, "附件地址必须使用 HTTP(S)");
        }
        MediaType type = switch (kind) {
            case "IMAGE", "POSTER", "QR_CODE" -> MediaType.IMAGE;
            case "PDF" -> MediaType.PDF;
            case "WORD" -> MediaType.WORD;
            case "LINK" -> MediaType.URL;
            default -> throw new IllegalStateException();
        };
        String objectKey = cosService.resolveManagedObjectKey(url.trim());
        var upload = objectKey == null ? null : uploadRecordService.lockAttachmentUpload(operatorId, objectKey);
        MediaAttachment entity = MediaAttachment.builder().type(type.getCode()).kind(kind)
                .registered(Boolean.TRUE).registeredBy(operatorId).objectKey(objectKey)
                .url(objectKey == null ? url.trim() : cosService.buildPublicUrl(objectKey))
                .originalName(name.trim()).sortOrder(0).build();
        if (mediaAttachmentMapper.insert(entity) != 1) {
            throw new SystemException("登记附件失败");
        }
        if (upload != null) uploadRecordService.bindRegisteredAttachment(upload, entity.getId());
        return mediaConverter.toBO(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void replaceAttachmentLinks(TargetType targetType, Long targetId, List<AttachmentLinkBO> links) {
        requireTargetId(targetId);
        if (targetType != TargetType.ACTIVITY && targetType != TargetType.EXAM) {
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
        Map<Long, MediaAttachmentBO> attachments = batchGetAttachments(ids);
        if (attachments.size() != ids.size()) throw new BusinessException(ResultCode.MEDIA_ATTACHMENT_NOT_FOUND);
        for (Long id : ids) {
            MediaAttachment entity = mediaAttachmentMapper.selectById(id);
            if (entity == null || !Boolean.TRUE.equals(entity.getRegistered())) {
                throw new BusinessException(ResultCode.MEDIA_ATTACHMENT_NOT_FOUND);
            }
        }
        mediaAttachmentMapper.deleteLinks(targetType.getCode(), targetId);
        values.stream().sorted(Comparator.comparingInt(AttachmentLinkBO::displayOrder)
                .thenComparing(AttachmentLinkBO::attachmentId))
                .forEach(link -> mediaAttachmentMapper.insertLink(targetType.getCode(), targetId,
                        link.attachmentId(), link.displayOrder()));
    }

    @Override
    public List<AttachmentTargetBO> listAttachmentTargets(Long attachmentId) {
        if (attachmentId == null || attachmentId <= 0) return List.of();
        return mediaAttachmentMapper.selectTargets(attachmentId);
    }

    /**
     * 查询目标附件列表。
     *
     * @param mediaType  附件类型
     * @param targetType 目标类型
     * @param targetId   目标 ID
     * @return 按 sortOrder、id 升序排列的附件视图列表
     * @throws BusinessException 参数非法或目标不存在
     */
    @Override
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

    /**
     * 查询目标附件列表（简化版）。
     *
     * @param targetType 目标类型
     * @param targetId   目标 ID
     * @return 按 sortOrder、id 升序排列的附件简化视图列表
     * @throws BusinessException 参数非法或目标不存在
     * @see #listAttachments(TargetType, Long)
     */
    @Override
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

    /**
     * 批量查询多个目标的附件列表，按 targetId 分组。
     *
     * @param targetType 目标类型
     * @param targetIds  目标 ID 列表
     * @return 返回一个 Map，key 是 targetId，value 是该目标的附件列表（按 sortOrder、id 升序排列）。如果某个 targetId 没有附件，则对应的 value 是一个空列表。
     */
    @Override
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

    /**
     * 生成前端直传 COS 的 STS 上传凭证。
     * <p>
     * 调用方直传 COS 后，普通媒体在业务保存事务中绑定目标；
     * 管理端可复用附件在独立登记事务中接管，主体只引用附件 ID。
     * <p>
     * 生成凭证后登记 PENDING 上传记录；超时未接管对象由定时任务清理。
     *
     * @param targetType 目标类型
     * @param fileNames  原始文件名
     * @return 上传凭证
     * @throws BusinessException 未登录或参数非法
     */
    @Override
    public CosUploadCredentialDTO generateUploadCredential(TargetType targetType, List<String> fileNames) {
        return generateCredential(targetType, fileNames, requireUserId());
    }

    @Override
    public CosUploadCredentialDTO generateAdminUploadCredential(TargetType targetType, List<String> fileNames, Long operatorId) {
        if (operatorId == null || operatorId <= 0) throw new BusinessException(ResultCode.UNAUTHORIZED);
        if (targetType != TargetType.ACTIVITY && targetType != TargetType.EXAM) {
            throw new BusinessException(ResultCode.MEDIA_TARGET_TYPE_UNSUPPORTED);
        }
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
        LocalDateTime now = LocalDateTime.now();
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
        String expectedPrefix = targetCategory(targetType) + "/" + userId + "/";
        if (!normalizedKey.startsWith(expectedPrefix) || normalizedKey.contains("..")) {
            throw new BusinessException(ResultCode.INVALID_OPERATION, "objectKey 不属于当前用户或业务类型");
        }
        return cosService.buildPublicUrl(normalizedKey);
    }

    @Override
    public ProfileMediaSnapshotBO prepareProfileSnapshot(String objectKey) {
        Long userId = requireUserId();
        resolveOwnedUploadUrl(TargetType.USER, objectKey);
        uploadRecordService.assertPendingProfileUpload(userId, objectKey);
        String snapshotKey = "profile-effective/" + userId + "/" + UUID.randomUUID().toString().replace("-", "");
        // Register before COS: a lost copy response still leaves a durable cleanup candidate.
        uploadRecordService.recordPending(userId, TargetType.USER, List.of(snapshotKey), LocalDateTime.now().plusDays(1));
        cosService.copyObject(objectKey, snapshotKey);
        return new ProfileMediaSnapshotBO(snapshotKey, cosService.buildPublicUrl(snapshotKey));
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

    /**
     * 获取当前登录用户 ID。
     *
     * @return 用户 ID
     * @throws BusinessException 未登录
     */
    private Long requireUserId() {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        return userId;
    }

    /**
     * 断言目标类型.
     *
     * @param targetType 目标类型
     * @throws BusinessException 类型不支持
     */
    private void assertTargetTypeAllowed(TargetType targetType) {
        if (!MEDIA_SUPPORTED_TYPES.contains(targetType)) {
            throw new BusinessException(ResultCode.MEDIA_TARGET_TYPE_UNSUPPORTED);
        }
    }

    /**
     * 校验 targetId 非空。
     *
     * @param targetId 目标 ID
     * @throws BusinessException targetId 为空
     */
    private void requireTargetId(Long targetId) {
        if (targetId == null) {
            throw new BusinessException(ResultCode.INVALID_OPERATION, "targetId 不能为空");
        }
    }

    /**
     * 构建 COS 对象路径。
     * <p>
     * 构建 COS objectKey。
     * <p>
     * 当前直接按业务类型归档：
     * post/{userId}/{timestamp}_{uuid}_{fileName}
     * <p>
     * <p>路径格式：{category}/{userId}/{timestamp}_{uuid}_{fileName}。
     * 上传记录负责生命周期，目录数字仅表示上传者，不表示绑定目标。</p>
     *
     * @param type     目标类型
     * @param userId   用户 ID
     * @param fileName 原始文件名
     * @return 对象键
     */
    private String buildObjectKey(TargetType type, Long userId, String fileName) {
        String safeName = sanitizeFileName(fileName);
        String category = targetCategory(type);
        String nonce = UUID.randomUUID().toString().replace("-", "");
        return category + "/" + userId + "/" + System.currentTimeMillis() + "_" + nonce + "_" + safeName;
    }

    private String targetCategory(TargetType type) {
        return switch (type) {
            case POST -> "post";
            case ACTIVITY -> "activity";
            case EXAM -> "exam";
            case COMMENT -> "comment";
            case USER -> "user";
            default -> throw new BusinessException(ResultCode.MEDIA_TARGET_TYPE_UNSUPPORTED);
        };
    }

    /**
     * 将前端附件描述收敛为服务端可信数据：COS URL 由 objectKey 生成，外链只允许 HTTPS。
     * 历史附件可能尚未回填 objectKey，仅临时允许已配置 COS/CDN 域名的 URL。
     */
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
            String validatedObjectKey = objectKey;
            boolean alreadyAttachedToTarget = existingAttachments.stream()
                    .anyMatch(existing -> Objects.equals(existing.getObjectKey(), validatedObjectKey));
            boolean ownedNewUpload = expectedPrefix != null && validatedObjectKey.startsWith(expectedPrefix);
            if ((!alreadyAttachedToTarget && !ownedNewUpload) || validatedObjectKey.contains("..")) {
                throw new BusinessException(ResultCode.INVALID_OPERATION, "objectKey 不属于当前用户或业务类型");
            }

            normalized.add(AttachmentItemBO.builder()
                    .type(item.getType())
                    .objectKey(validatedObjectKey)
                    .url(cosService.buildPublicUrl(validatedObjectKey))
                    .originalName(item.getOriginalName())
                    .sortOrder(item.getSortOrder())
                    .build());
        }
        return normalized;
    }

    /**
     * 在业务事务中持久化已解除引用对象的删除意图；现有清理任务在提交后执行并恢复失败。
     */
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

    /**
     * 清洗文件名，防止路径穿透并规整大小写。
     *
     * @param fileName 原始文件名
     * @return 清洗后的文件名
     * @throws BusinessException 文件名为空或非法
     */
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
