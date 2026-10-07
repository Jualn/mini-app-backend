package cn.jualn.miniapp.module.user.audit;

import cn.jualn.miniapp.common.enums.AuditScene;
import cn.jualn.miniapp.common.enums.MediaType;
import cn.jualn.miniapp.module.audit.bo.AuditReserveBO;
import cn.jualn.miniapp.module.audit.bo.AuditReserveResultBO;
import cn.jualn.miniapp.module.audit.service.AuditReservationService;
import cn.jualn.miniapp.module.user.bo.UserProfileUpdateBO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 用户资料审核提交器。
 *
 * <p>负责将用户资料字段拆成独立审核场景并投递审核队列。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserProfileAuditSubmitter {

    private static final Integer WX_SCENE_PROFILE = 1;
    private static final Integer MEDIA_TYPE_IMAGE_CODE = 2;

    private final AuditReservationService auditReservationService;

    public void submit(Long userId, UserProfileUpdateBO bo) {
        if (userId == null || bo == null) {
            return;
        }

        submitText(userId, AuditScene.USER_NICKNAME, bo.getNickname());
        submitText(userId, AuditScene.USER_BIO, bo.getBio());
        submitMedia(userId, AuditScene.USER_AVATAR, bo.getAvatarUrl());
        submitMedia(userId, AuditScene.USER_BACKGROUND, bo.getBackgroundUrl());
    }

    private void submitText(Long userId, AuditScene auditScene, String content) {
        if (!StringUtils.hasText(content)) {
            return;
        }

        auditReservationService.reserveAuditLogs(
                AuditReserveBO.builder()
                        .auditScene(auditScene)
                        .targetId(userId)
                        .textContent(content)
                        .build()
        );

    }

    private void submitMedia(Long userId, AuditScene auditScene, String mediaUrl) {
        if (!StringUtils.hasText(mediaUrl)) {
            return;
        }

        auditReservationService.reserveAuditLogs(
                AuditReserveBO.builder()
                        .auditScene(auditScene)
                        .targetId(userId)
                        .mediaItems(List.of(
                                AuditReserveBO.MediaItem.builder()
                                        .mediaType(MediaType.fromCode(MEDIA_TYPE_IMAGE_CODE))
                                        .mediaUrl(mediaUrl)
                                        .build()
                        ))
                        .build()
        );

    }
}
