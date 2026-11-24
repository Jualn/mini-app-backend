package cn.jualn.miniapp.module.media.service.impl;

import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.common.security.AdminStpUtil;
import cn.jualn.miniapp.module.activity.service.ActivityPublicAccess;
import cn.jualn.miniapp.module.admin.auth.support.AdminPermissionPolicy;
import cn.jualn.miniapp.module.publicevent.service.PublicEventPublicAccess;
import cn.jualn.miniapp.module.media.bo.MediaAttachmentBO;
import cn.jualn.miniapp.module.media.service.AttachmentReadService;
import cn.jualn.miniapp.module.media.service.MediaQueries;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Separate read composition avoids making the media writer depend back on its subject services. */
@Service
@RequiredArgsConstructor
public class AttachmentReadServiceImpl implements AttachmentReadService {

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.beans.factory.ObjectProvider<cn.jualn.miniapp.module.media.service.MediaDeliveryUrls> deliveryUrls;
    private final MediaQueries mediaService;
    private final cn.jualn.miniapp.module.media.service.MediaAccess mediaAccess;
    private final ActivityPublicAccess activityService;
    private final PublicEventPublicAccess examService;
    private final cn.jualn.miniapp.module.officialcontent.service.OfficialContentService officialContentService;
    private final cn.jualn.miniapp.module.article.service.ArticleService articleService;
    private final cn.jualn.miniapp.module.post.service.PostPublicAccess posts;

    @Override
    public MediaAttachmentBO getPublicAttachment(Long attachmentId) {
        MediaAttachmentBO value = mediaService.getAttachment(attachmentId);
        if("CANDIDATE".equals(value.getRetention())) throw new BusinessException(ResultCode.MEDIA_ATTACHMENT_NOT_FOUND);
        boolean visible = false;
        for (var target : mediaService.listAttachmentTargets(attachmentId)) {
            if(Objects.equals(TargetType.POST.getCode(),target.targetType())) visible |= posts.isPubliclyVisible(target.targetId());
            if (Objects.equals(TargetType.ACTIVITY.getCode(), target.targetType())) {
                visible |= activityService.isPubliclyVisible(target.targetId());
            } else if (Objects.equals(TargetType.EXAM.getCode(), target.targetType())) {
                visible |= examService.isPubliclyVisible(target.targetId());
            }
            if (Objects.equals(TargetType.OFFICIAL_CONTENT.getCode(), target.targetType())) {
                visible |= officialContentService.isPubliclyVisible(target.targetId());
            }
            if (Objects.equals(TargetType.ARTICLE.getCode(), target.targetType())) {
                visible |= articleService.isPubliclyVisible(target.targetId());
            }
            if (visible) break;
        }
        if (!visible) {
            throw new BusinessException(ResultCode.MEDIA_ATTACHMENT_NOT_FOUND);
        }
        return value;
    }

    @Override
    public MediaAttachmentBO getAdminAttachment(Long attachmentId) {
        try {
            mediaAccess.authorizeReusableRead(attachmentId,AdminStpUtil.STP_LOGIC.getLoginIdAsLong());
            var value=mediaService.getAttachment(attachmentId);
            if("VIDEO".equals(value.getKind()) || (value.getObjectKey()!=null && value.getObjectKey().startsWith("article/retained/"))) mediaAccess.authorizeArticleMedia();
            if(value.getUrl()!=null && value.getUrl().contains(cn.jualn.miniapp.module.media.service.MediaDeliveryUrls.PATH))value.setUrl(deliveryUrls.getObject().adminVariant(value.getUrl()));
            return value;
        } catch (BusinessException forbidden) {
            if(forbidden.getResultCode()==ResultCode.FORBIDDEN) throw new BusinessException(ResultCode.MEDIA_ATTACHMENT_NOT_FOUND);
            throw forbidden;
        } catch (cn.jualn.miniapp.common.exception.ContractProblemException forbidden) {
            if(forbidden.getStatus()==org.springframework.http.HttpStatus.FORBIDDEN) throw new BusinessException(ResultCode.MEDIA_ATTACHMENT_NOT_FOUND);
            throw forbidden;
        }
    }
}
