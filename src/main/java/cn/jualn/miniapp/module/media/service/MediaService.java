package cn.jualn.miniapp.module.media.service;

import cn.jualn.miniapp.module.media.bo.ProfileMediaSnapshotBO;

import cn.jualn.miniapp.common.enums.MediaType;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.module.media.bo.MediaAttachmentBO;
import cn.jualn.miniapp.module.media.bo.MediaAttachmentSaveBO;
import cn.jualn.miniapp.module.media.bo.MediaAttachmentSimpleBO;
import cn.jualn.miniapp.module.media.bo.AttachmentLinkBO;
import cn.jualn.miniapp.module.media.bo.AttachmentTargetBO;
import cn.jualn.miniapp.third.cos.dto.CosUploadCredentialDTO;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 通用附件服务（服务层接口）。
 *
 * <p>约定：service 间只传 DTO，不直接传 Request/VO。</p>
 */
public interface MediaService {

    ProfileMediaSnapshotBO prepareProfileSnapshot(String objectKey);

    /**
     * 覆盖保存目标附件。
     *
     * <p>语义为全量覆盖：会先删除目标已有附件，再按传入列表重建。</p>
     * <p>调用方业务服务必须先完成目标的创建/编辑权限校验。</p>
     *
    * @param saveDTO 附件保存 DTO
     */
    void replaceAttachments(MediaAttachmentSaveBO saveDTO);

    /**
     * 查询目标附件列表。
     *
     * @param targetType 目标类型：1-帖子 2-活动 3-考试信息
     * @param targetId   目标 ID
    * @return 按展示顺序排序后的附件 BO 列表
     */
    List<MediaAttachmentBO> listAttachments(TargetType targetType, Long targetId);
    MediaAttachmentBO getAttachment(Long attachmentId);

    /** Batch lookup for already-authorized aggregate projections such as list covers. */
    Map<Long, MediaAttachmentBO> batchGetAttachments(Collection<Long> attachmentIds);

    MediaAttachmentBO registerAttachment(String kind, String name, String url, Long operatorId);

    void replaceAttachmentLinks(TargetType targetType, Long targetId, List<AttachmentLinkBO> links);

    List<AttachmentTargetBO> listAttachmentTargets(Long attachmentId);

    List<MediaAttachmentBO> listAttachments(MediaType mediaType, TargetType targetType, Long targetId);

    List<MediaAttachmentSimpleBO> listSimpleAttachments(TargetType targetType, Long targetId);

    Map<Long, List<MediaAttachmentSimpleBO>> batchListSimpleAttachments(TargetType targetType, Collection<Long> targetIds);

    /**
     * 获取前端直传 COS 的 STS 上传凭证。
     *
     * @param targetType 目标类型：1-帖子 2-活动 3-考试信息
     * @param fileNames   原始文件名
     * @return 上传凭证信息
     */
    CosUploadCredentialDTO generateUploadCredential(TargetType targetType, List<String> fileNames);

    /** 管理端以已认证 operatorId 申请活动／公共事项上传，不借用普通用户 ThreadLocal 身份。 */
    CosUploadCredentialDTO generateAdminUploadCredential(TargetType targetType, List<String> fileNames, Long operatorId);

    /** 校验对象属于当前小程序用户及业务类型，并生成服务端可信访问地址。 */
    String resolveOwnedUploadUrl(TargetType targetType, String objectKey);

    /** 在业务事务内把当前用户的待绑定上传记录绑定到目标。 */
    void bindPendingUploads(TargetType targetType, Long targetId, Collection<String> objectKeys);

    /** 在当前业务事务内持久化删除意图；提交后由清理任务执行。软删除不调用此方法。 */
    void deleteObjectsAfterCommit(Collection<String> objectKeys, TargetType targetType, Long targetId);
}
