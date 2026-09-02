package cn.jualn.miniapp.module.user.audit;

import cn.jualn.miniapp.common.enums.AuditScene;
import cn.jualn.miniapp.common.enums.MediaType;
import cn.jualn.miniapp.infrastructure.queue.contract.QueueProducer;
import cn.jualn.miniapp.module.audit.bo.AuditReserveBO;
import cn.jualn.miniapp.module.audit.bo.AuditReserveResultBO;
import cn.jualn.miniapp.module.audit.payload.AuditMediaBatchPayload;
import cn.jualn.miniapp.module.audit.payload.AuditTextPayload;
import cn.jualn.miniapp.module.audit.service.AuditReservationService;
import cn.jualn.miniapp.module.user.bo.UserProfileUpdateBO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Objects;

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
    private final QueueProducer queueProducer;

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

        AuditReserveResultBO reserveResult = auditReservationService.reserveAuditLogs(
                AuditReserveBO.builder()
                        .auditScene(auditScene)
                        .targetId(userId)
                        .textContent(content)
                        .build()
        );

        if (reserveResult == null || reserveResult.getTextAuditLogId() == null) {
            return;
        }

        queueProducer.send(AuditTextPayload.builder()
                .auditLogId(reserveResult.getTextAuditLogId())
                .auditScene(auditScene)
                .targetId(userId)
                .content(content)
                .scene(WX_SCENE_PROFILE)
                .build());

        log.info("[UserProfileAuditSubmitter] 用户资料文本审核已投递，userId={}, auditScene={}",
                userId, auditScene);
    }

    private void submitMedia(Long userId, AuditScene auditScene, String mediaUrl) {
        if (!StringUtils.hasText(mediaUrl)) {
            return;
        }

        AuditReserveResultBO reserveResult = auditReservationService.reserveAuditLogs(
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

        if (reserveResult == null || CollectionUtils.isEmpty(reserveResult.getMediaItems())) {
            return;
        }

        List<AuditMediaBatchPayload.AuditMediaItem> items = reserveResult.getMediaItems().stream()
                .filter(Objects::nonNull)
                .map(item -> AuditMediaBatchPayload.AuditMediaItem.builder()
                        .auditLogId(item.getAuditLogId())
                        .mediaUrl(item.getMediaUrl())
                        .mediaType(item.getMediaType())
                        .build())
                .toList();

        if (items.isEmpty()) {
            return;
        }

        queueProducer.send(AuditMediaBatchPayload.builder()
                .auditScene(auditScene)
                .targetId(userId)
                .scene(WX_SCENE_PROFILE)
                .items(items)
                .build());

        log.info("[UserProfileAuditSubmitter] 用户资料媒体审核已投递，userId={}, auditScene={}",
                userId, auditScene);
    }
}
