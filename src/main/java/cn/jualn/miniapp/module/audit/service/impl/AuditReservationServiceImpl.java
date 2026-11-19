package cn.jualn.miniapp.module.audit.service.impl;

import cn.jualn.miniapp.module.audit.enums.AuditScene;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.common.observability.ObservabilityContext;
import cn.jualn.miniapp.infrastructure.async.job.JobDefinition;
import cn.jualn.miniapp.infrastructure.async.job.JobService;
import cn.jualn.miniapp.module.audit.async.AuditMediaJobPayload;
import cn.jualn.miniapp.module.audit.async.AuditTextJobPayload;
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
import java.time.LocalDateTime;

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
                    AuditScene.USER_BACKGROUND,
                    AuditScene.ARTICLE,
                    AuditScene.POST_EDIT,
                    AuditScene.OFFICIAL_CONTENT_CANDIDATE
            );

    private final ContentAuditLogMapper contentAuditLogMapper;
    private final JobService jobService;
    @org.springframework.beans.factory.annotation.Autowired
    private cn.jualn.miniapp.module.audit.service.ContentSafetyReviewService reviews;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AuditReserveResultBO reserveAuditLogs(AuditReserveBO bo) {
        if (bo == null) {
            throw new BusinessException(ResultCode.AUDIT_PARAM_INVALID, "审核预占参数不能为空");
        }

        assertAuditScene(bo.getAuditScene());
        Long actor=(bo.getAuditScene()==AuditScene.ARTICLE || bo.getAuditScene()==AuditScene.OFFICIAL_CONTENT_CANDIDATE) ? bo.getAuthenticatedActorAccountId() : UserContext.getUserId();
        if((bo.getAuditScene()==AuditScene.ARTICLE || bo.getAuditScene()==AuditScene.OFFICIAL_CONTENT_CANDIDATE) && (actor==null || actor<=0)) throw new BusinessException(ResultCode.AUDIT_PARAM_INVALID,"Article audit requires trusted actor context");

        if (bo.getTargetId() == null) {
            throw new BusinessException(ResultCode.AUDIT_PARAM_INVALID, "targetId 不能为空");
        }

        Long textAuditLogId = null;
        List<Long> textAuditLogIds = new ArrayList<>();
        List<AuditReserveResultBO.MediaItem> mediaResults = new ArrayList<>();

        if (StringUtils.hasText(bo.getTextContent())) {
            for(String text: textChunks(bo)) {
            ContentAuditLog textLog = ContentAuditLog.builder()
                    .targetType(bo.getAuditScene().getCode())
                    .targetId(bo.getTargetId())
                    .auditSource(AuditSourceEnum.WX_AUTO.getCode())
                    .finalResult(AuditStatus.PENDING.getCode())
                    .build();

            contentAuditLogMapper.insert(textLog);
            textAuditLogIds.add(textLog.getId());
            if(textAuditLogId==null) textAuditLogId = textLog.getId();
            jobService.create(new JobDefinition("audit.text.submit", 1, ObservabilityContext.ensureOperationId(),
                    "audit-log:" + textLog.getId(), "content-audit-log", String.valueOf(textLog.getId()),
                    new AuditTextJobPayload(textLog.getId(), bo.getAuditScene(), bo.getTargetId(),
                            text, wxScene(bo.getAuditScene()), actor),
                    LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")), 4));
            }
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

                jobService.create(new JobDefinition("audit.media.submit", 1, ObservabilityContext.ensureOperationId(),
                        "audit-log:" + mediaLog.getId(), "content-audit-log", String.valueOf(mediaLog.getId()),
                        new AuditMediaJobPayload(mediaLog.getId(), bo.getAuditScene(), bo.getTargetId(),
                                item.getMediaUrl(), item.getMediaType(), wxScene(bo.getAuditScene()), actor),
                        LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")), 4));

                mediaResults.add(AuditReserveResultBO.MediaItem.builder()
                        .auditLogId(mediaLog.getId())
                        .mediaType(item.getMediaType())
                        .mediaUrl(item.getMediaUrl())
                        .build());
            }
        }

        var result = AuditReserveResultBO.builder()
                .textAuditLogId(textAuditLogId).textAuditLogIds(textAuditLogIds)
                .mediaItems(mediaResults)
                .build();
        reviews.reserve(bo, result);
        return result;
    }

    private List<String> textChunks(AuditReserveBO input) {
        if(!cn.jualn.miniapp.module.audit.service.ContentSafetyReviewService.supported(input.getAuditScene())) return List.of(input.getTextContent());
        var chunks=new ArrayList<String>();String text=input.getTextContent();
        for(int start=0;start<text.length();) {
            int end=text.offsetByCodePoints(start,Math.min(2000,text.codePointCount(start,text.length())));
            chunks.add(text.substring(start,end));start=end;
        }
        return chunks;
    }

    private int wxScene(AuditScene scene) {
        return switch (scene) {
            case USER_NICKNAME, USER_AVATAR, USER_BIO, USER_BACKGROUND -> 1;
            case COMMENT -> 2;
            default -> 3;
        };
    }

    private void assertAuditScene(AuditScene auditScene) {
        if (!AUDIT_ALLOWED_SCENES.contains(auditScene)) {
            throw new BusinessException(ResultCode.AUDIT_SCENE_UNSUPPORTED, "不支持的 auditScene: " + auditScene);
        }
    }
}
