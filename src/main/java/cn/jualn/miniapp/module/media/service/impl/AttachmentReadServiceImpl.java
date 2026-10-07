package cn.jualn.miniapp.module.media.service.impl;

import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.common.security.AdminStpUtil;
import cn.jualn.miniapp.module.activity.service.ActivityService;
import cn.jualn.miniapp.module.admin.auth.support.AdminPermissionPolicy;
import cn.jualn.miniapp.module.exam.service.ExamService;
import cn.jualn.miniapp.module.media.bo.MediaAttachmentBO;
import cn.jualn.miniapp.module.media.service.AttachmentReadService;
import cn.jualn.miniapp.module.media.service.MediaService;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Separate read composition avoids making the media writer depend back on its subject services. */
@Service
@RequiredArgsConstructor
public class AttachmentReadServiceImpl implements AttachmentReadService {
    private final MediaService mediaService;
    private final ActivityService activityService;
    private final ExamService examService;

    @Override
    public MediaAttachmentBO getPublicAttachment(Long attachmentId) {
        MediaAttachmentBO value = mediaService.getAttachment(attachmentId);
        boolean visible = false;
        for (var target : mediaService.listAttachmentTargets(attachmentId)) {
            if (Objects.equals(TargetType.ACTIVITY.getCode(), target.targetType())) {
                visible |= activityService.isPubliclyVisible(target.targetId());
            } else if (Objects.equals(TargetType.EXAM.getCode(), target.targetType())) {
                visible |= examService.isPubliclyVisible(target.targetId());
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
        AdminStpUtil.STP_LOGIC.checkPermission(AdminPermissionPolicy.CONTENT_MANAGE);
        return mediaService.getAttachment(attachmentId);
    }
}
