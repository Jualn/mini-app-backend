package cn.jualn.miniapp.module.media.service;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.module.media.bo.*;
import cn.jualn.miniapp.third.cos.dto.CosUploadCredentialDTO;
import java.util.*;
/** Media preparation and lifecycle writes. The caller owns subject authorization. */
public interface MediaService {
    List<cn.jualn.miniapp.module.media.bo.AttachmentItemBO> prepareAttachments(TargetType targetType, Long targetId, List<cn.jualn.miniapp.module.media.bo.AttachmentItemBO> items);

    List<cn.jualn.miniapp.module.media.bo.AttachmentItemBO> prepareNewAttachments(TargetType type,List<cn.jualn.miniapp.module.media.bo.AttachmentItemBO> items);

    String prepareOwnedImage(TargetType type,String key);

    ProfileMediaSnapshotBO prepareProfileSnapshot(String objectKey);

    /** Preserve resource identity while applying the authorized complete attachment declaration. */
    void replaceAttachments(MediaAttachmentSaveBO saveDTO);

    /** Retired unkeyed operation: always rejects writes. Use AttachmentRegistrationService. */
    @Deprecated
    MediaAttachmentBO registerAttachment(String kind, String name, String url, Long operatorId);

    CosUploadCredentialDTO generateArticleUploadCredential(List<ArticleUploadFileBO> files, long operatorId);

    void replaceAttachmentLinks(TargetType targetType, Long targetId, List<AttachmentLinkBO> links);

    CosUploadCredentialDTO generateUploadCredential(TargetType targetType, List<String> fileNames);

    CosUploadCredentialDTO generateAdminUploadCredential(TargetType targetType, List<String> fileNames, Long operatorId);

    String resolveOwnedUploadUrl(TargetType targetType, String objectKey);

    /** Bind verified pending uploads in the caller's business transaction. */
    void bindPendingUploads(TargetType targetType, Long targetId, Collection<String> objectKeys);

    /** Persist deletion intent in the business transaction; the cleanup worker performs remote deletion. */
    void deleteObjectsAfterCommit(Collection<String> objectKeys, TargetType targetType, Long targetId);
}
