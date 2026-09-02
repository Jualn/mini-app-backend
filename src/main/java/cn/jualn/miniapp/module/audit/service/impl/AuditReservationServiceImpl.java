package cn.jualn.miniapp.module.audit.service.impl;

import cn.jualn.miniapp.common.enums.AuditScene;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.module.audit.bo.AuditReserveBO;
import cn.jualn.miniapp.module.audit.bo.AuditReserveResultBO;
import cn.jualn.miniapp.module.audit.entity.ContentAuditLog;
import cn.jualn.miniapp.module.audit.enums.AuditSourceEnum;
import cn.jualn.miniapp.module.audit.enums.AuditStatus;
import cn.jualn.miniapp.module.audit.mapper.ContentAuditLogMapper;
import cn.jualn.miniapp.module.audit.service.AuditReservationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class AuditReservationServiceImpl implements AuditReservationService {

    private static final Set<AuditScene> AUDIT_ALLOWED_SCENES =
            Set.of(
                    AuditScene.POST,
                    AuditScene.COMMENT,
                    AuditScene.ACTIVITY,
                    AuditScene.EXAM,
                    AuditScene.USER_NICKNAME,
                    AuditScene.USER_AVATAR,
                    AuditScene.USER_BIO,
                    AuditScene.USER_BACKGROUND
            );

    private final ContentAuditLogMapper contentAuditLogMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AuditReserveResultBO reserveAuditLogs(AuditReserveBO bo) {
        if (bo == null) {
            throw new BusinessException(ResultCode.AUDIT_PARAM_INVALID, "审核预占参数不能为空");
        }

        assertAuditScene(bo.getAuditScene());

        if (bo.getTargetId() == null) {
            throw new BusinessException(ResultCode.AUDIT_PARAM_INVALID, "targetId 不能为空");
        }

        Long textAuditLogId = null;
        List<AuditReserveResultBO.MediaItem> mediaResults = new ArrayList<>();

        if (StringUtils.hasText(bo.getTextContent())) {
            ContentAuditLog textLog = ContentAuditLog.builder()
                    .targetType(bo.getAuditScene().getCode())
                    .targetId(bo.getTargetId())
                    .auditSource(AuditSourceEnum.WX_AUTO.getCode())
                    .finalResult(AuditStatus.PENDING.getCode())
                    .build();

            contentAuditLogMapper.insert(textLog);
            textAuditLogId = textLog.getId();
        }

        if (!CollectionUtils.isEmpty(bo.getMediaItems())) {
            for (AuditReserveBO.MediaItem item : bo.getMediaItems()) {
                if (item == null || !StringUtils.hasText(item.getMediaUrl())) {
                    continue;
                }

                ContentAuditLog mediaLog = ContentAuditLog.builder()
                        .targetType(bo.getAuditScene().getCode())
                        .targetId(bo.getTargetId())
                        .auditSource(AuditSourceEnum.WX_AUTO.getCode())
                        .finalResult(AuditStatus.PENDING.getCode())
                        .build();

                contentAuditLogMapper.insert(mediaLog);

                mediaResults.add(AuditReserveResultBO.MediaItem.builder()
                        .auditLogId(mediaLog.getId())
                        .mediaType(item.getMediaType())
                        .mediaUrl(item.getMediaUrl())
                        .build());
            }
        }

        return AuditReserveResultBO.builder()
                .textAuditLogId(textAuditLogId)
                .mediaItems(mediaResults)
                .build();
    }

    private void assertAuditScene(AuditScene auditScene) {
        if (!AUDIT_ALLOWED_SCENES.contains(auditScene)) {
            throw new BusinessException(ResultCode.AUDIT_SCENE_UNSUPPORTED, "不支持的 auditScene: " + auditScene);
        }
    }
}
