package cn.jualn.miniapp.module.media.service.impl;

import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.common.enums.MediaType;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.exception.SystemException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.infrastructure.validator.TargetValidator;
import cn.jualn.miniapp.module.media.bo.MediaAttachmentSimpleBO;
import cn.jualn.miniapp.module.media.bo.AttachmentItemBO;
import cn.jualn.miniapp.module.media.converter.MediaConverter;
import cn.jualn.miniapp.module.media.bo.MediaAttachmentBO;
import cn.jualn.miniapp.module.media.bo.MediaAttachmentSaveBO;
import cn.jualn.miniapp.module.media.entity.MediaAttachment;
import cn.jualn.miniapp.module.media.mapper.MediaAttachmentMapper;
import cn.jualn.miniapp.module.media.service.MediaService;
import cn.jualn.miniapp.third.cos.dto.CosUploadCredentialDTO;
import cn.jualn.miniapp.third.cos.service.CosService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

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

    /**
     * 覆盖保存目标附件。
     *
     * <p>写入语义：先删后插，保持数据与前端提交结果一致。</p>
     *
     * @param saveDTO <p>目标类型：{@link TargetType}</p>
     *                <p>目标 ID</p>
     *                <p>附件列表</p>
     * @throws BusinessException 未登录、参数非法或目标不存在
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void replaceAttachments(MediaAttachmentSaveBO saveDTO) {
        Long userId = requireUserId();
        Long targetId = saveDTO.getTargetId();
        TargetType targetType = saveDTO.getTargetType();
        List<AttachmentItemBO> attachments = saveDTO.getAttachments();
        long start = System.currentTimeMillis();
        log.info("[MediaService.replaceAttachments][开始] userId={}, targetType={}, targetId={}", userId, targetType, targetId);

        if (CollectionUtils.isEmpty(attachments)) {
            // sortOrder 为空时按入参顺序自动补位，保证展示稳定。
            throw new BusinessException(ResultCode.MEDIA_ATTACHMENT_EMPTY);
        }
        requireTargetId(targetId);
        assertTargetTypeAllowed(targetType);

        targetValidator.assertExists(targetType, targetId);

        // 通过删除然后覆写，就可以不用再写接口更新target的attachments了，统一覆写
        mediaAttachmentMapper.delete(new LambdaQueryWrapper<MediaAttachment>()
                .eq(MediaAttachment::getTargetType, targetType.getCode())
                .eq(MediaAttachment::getTargetId, targetId));

        // 构建一个列表，用于存储批量插入的数据
        List<MediaAttachment> mediaAttachments = mediaConverter.toMediaAttachmentList(saveDTO);
        if (mediaAttachments.isEmpty()) {
            log.error("[MediaService.replaceAttachments][附件转换失败] userId={}, targetType={}, targetId={}",
                    userId, targetType, targetId);
            throw new SystemException("附件转换失败");
        }

        mediaAttachmentMapper.insertBatch(mediaAttachments);

        log.info("[MediaService.replaceAttachments][完成] userId={}, targetType={}, targetId={}, count={}, costMs={}",
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

        List<MediaAttachment> attachments = mediaAttachmentMapper.selectList(new LambdaQueryWrapper<MediaAttachment>()
                .eq(MediaAttachment::getTargetType, targetType)
                .eq(MediaAttachment::getTargetId, targetId)
                .orderByAsc(MediaAttachment::getSortOrder)
                .orderByAsc(MediaAttachment::getId));

        return mediaConverter.toBOList(attachments);
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
                .eq(MediaAttachment::getTargetType, targetType)
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
     * 删除单条附件。
     *
     * @param attachmentId 附件 ID
     * @throws BusinessException 未登录、参数非法或附件不存在
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeAttachment(Long attachmentId) {
        Long userId = requireUserId();
        if (attachmentId == null) {
            throw new BusinessException(ResultCode.INVALID_OPERATION, "attachmentId 不能为空");
        }

        int deletedRows = mediaAttachmentMapper.deleteById(attachmentId);
        if (deletedRows == 0) {
            throw new BusinessException(ResultCode.MEDIA_ATTACHMENT_NOT_FOUND);
        }
        log.info("[MediaService.removeAttachment][完成] userId={}, attachmentId={}", userId, attachmentId);
    }

    /**
     * 生成前端直传 COS 的 STS 上传凭证。
     * <p>
     * 当前设计：
     * 1. 前端选择图片/文件时不调用本接口；
     * 2. 仅在用户确认发布 post/comment/activity/exam 时调用；
     * 3. 前端上传 COS 成功后，将访问地址随业务内容一起提交。
     * <p>
     * TODO:
     *  当前暂未引入上传临时表、PENDING/USED 状态流转、定时清理任务。
     *  如果后续观察到 COS 中出现较多无业务引用的孤儿文件，
     *  再考虑增加 media_upload_temp 表，记录 objectKey 生命周期，
     *  并通过定时任务清理超时未绑定业务数据的对象。
     * <p>
     * 注意：
     * 如果前端在“选择图片后立即上传”，用户取消编辑或退出页面时，
     * COS 可能产生无引用文件。因此前端必须保持“最终提交时才上传”。
     *
     * @param targetType 目标类型
     * @param fileNames  原始文件名
     * @return 上传凭证
     * @throws BusinessException 未登录或参数非法
     */
    @Override
    public CosUploadCredentialDTO generateUploadCredential(TargetType targetType, List<String> fileNames) {
        Long userId = requireUserId();
        assertTargetTypeAllowed(targetType);

        List<String> objectKeys = new ArrayList<>(fileNames.size());
        for (String fileName : fileNames) {
            objectKeys.add(
                    buildObjectKey(targetType, userId, fileName)
            );
        }
        return cosService.generateUploadCredential(objectKeys);
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
     * TODO:
     *  如果后续增加临时上传保护，可以改为：
     *  temp/post/{userId}/{timestamp}_{uuid}_{fileName}
     *  并在业务提交成功后标记为 USED，或迁移为正式对象。
     * <p>
     * 现阶段为了减少数据库表、定时任务和额外服务器负担，
     * 暂不引入上传生命周期管理。
     *
     * <p>路径格式：{category}/{userId}/{timestamp}_{uuid}_{fileName}</p>
     *
     * @param type     目标类型
     * @param userId   用户 ID
     * @param fileName 原始文件名
     * @return 对象键
     */
    private String buildObjectKey(TargetType type, Long userId, String fileName) {
        String safeName = sanitizeFileName(fileName);
        String category = switch (type) {
            case POST -> "post";
            case ACTIVITY -> "activity";
            case EXAM -> "exam";
            case COMMENT -> "comment";
            case USER -> "user";
            default -> throw new BusinessException(ResultCode.MEDIA_TARGET_TYPE_UNSUPPORTED);
        };
        String nonce = UUID.randomUUID().toString().replace("-", "");
        return category + "/" + userId + "/" + System.currentTimeMillis() + "_" + nonce + "_" + safeName;
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
