package cn.jualn.miniapp.module.media.service;

import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.module.media.bo.MediaAttachmentBO;
import cn.jualn.miniapp.module.media.mapper.MediaAttachmentMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.util.Objects;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class PublicImageReuse {
    private static final Set<TargetType> PUBLIC_PURPOSES =
            Set.of(TargetType.ARTICLE, TargetType.ACTIVITY, TargetType.EXAM, TargetType.OFFICIAL_CONTENT);

    private final MediaAttachmentMapper attachments;
    private final MediaQueries queries;
    private final MediaAccess access;
    private final AttachmentReadService reads;
    private final MediaUploadRecordService uploads;
    private final MediaDeliveryUrls urls;
    private final MediaContentService content;

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean lockReusable(long id, long actor) {
        var row = attachments.lock(id);
        if (row == null || !Boolean.TRUE.equals(row.getRegistered()) || Boolean.TRUE.equals(row.getDeleted())
                || !Objects.equals(row.getRegisteredBy(), actor) || !"FORMAL".equals(row.getRetention())
                || !"IMAGE".equals(row.getKind())) return false;
        try {
            access.authorizeReusableRead(id, actor);
            return reusableUrl(id) != null;
        } catch (BusinessException e) {
            if (e.getResultCode() == ResultCode.FORBIDDEN || e.getResultCode() == ResultCode.MEDIA_ATTACHMENT_NOT_FOUND)
                return false;
            throw e;
        } catch (ContractProblemException e) {
            if (e.getStatus() == HttpStatus.FORBIDDEN || e.getStatus() == HttpStatus.NOT_FOUND) return false;
            throw e;
        }
    }

    public String reusableUrl(long id) {
        try {
            var row = queries.getAttachment(id);
            if (!"FORMAL".equals(row.getRetention()) || !"IMAGE".equals(row.getKind())) return null;
            String url = publicUrl(id);
            if (url != null) return url;

            // An unlinked formal image can be reused only with a proven public upload purpose.
            if (row.getTargetType() != null || row.getTargetId() != null || row.getRegisteredBy() == null
                    || !queries.listAttachmentTargets(id).isEmpty()) return null;
            TargetType purpose = uploads.registeredPurpose(id, row.getRegisteredBy());
            return purpose != null && PUBLIC_PURPOSES.contains(purpose) ? imageUrl(row) : null;
        } catch (BusinessException e) {
            if (e.getResultCode() == ResultCode.MEDIA_ATTACHMENT_NOT_FOUND) return null;
            throw e;
        }
    }

    public String publicUrl(Long id) {
        if (id == null) return null;
        try {
            return imageUrl(reads.getPublicAttachment(id));
        } catch (BusinessException e) {
            if (e.getResultCode() == ResultCode.MEDIA_ATTACHMENT_NOT_FOUND) return null;
            throw e;
        }
    }

    private String imageUrl(MediaAttachmentBO row) {
        String url = row.getUrl();
        if (!"IMAGE".equals(row.getKind()) || url == null) return null;
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (uri.getHost() == null || uri.getUserInfo() != null
                || !("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))) return null;
        if (uri.getPath().contains(MediaDeliveryUrls.PATH)) {
            Long fileId = urls.fileId(url);
            if (fileId == null) return null;
            try {
                content.require(fileId, null, false);
            } catch (ContractProblemException e) {
                if (e.getStatus() == HttpStatus.NOT_FOUND) return null;
                throw e;
            }
        }
        return url;
    }
}
