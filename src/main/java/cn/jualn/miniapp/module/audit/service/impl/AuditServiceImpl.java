package cn.jualn.miniapp.module.audit.service.impl;

import cn.jualn.miniapp.common.constant.RedisKeyConstant;
import cn.jualn.miniapp.common.enums.AuditScene;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.module.audit.bo.*;
import cn.jualn.miniapp.module.audit.entity.ContentAuditLog;
import cn.jualn.miniapp.module.audit.enums.AuditStatus;
import cn.jualn.miniapp.module.audit.mapper.ContentAuditLogMapper;
import cn.jualn.miniapp.module.audit.service.AuditService;
import cn.jualn.miniapp.module.wx.dto.WxaMediaCheckMessage;
import cn.jualn.miniapp.third.wx.client.WxClient;
import cn.jualn.miniapp.third.wx.dto.WxMediaCheckAsyncRequest;
import cn.jualn.miniapp.third.wx.dto.WxMediaCheckAsyncResponse;
import cn.jualn.miniapp.third.wx.dto.WxMsgSecCheckRequest;
import cn.jualn.miniapp.third.wx.dto.WxMsgSecCheckResponse;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditServiceImpl implements AuditService {

    private static final Integer DEFAULT_SCENE = 3;
    private static final String SUGGEST_PASS = "pass";

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

    private final WxClient wxClient;
    private final ContentAuditLogMapper contentAuditLogMapper;
    private final RedisService redisService;
    private final ObjectMapper objectMapper;
    private final AuditResultPersistenceService resultPersistenceService;

    /**
     * 文本审核是“外部微信调用 + 本地落库”的组合流程。
     * <p>这里不加外层事务，避免将远程调用纳入本地事务后带来不必要的锁持有和回滚复杂度。</p>
     */
    @Override
    public AuditCheckResultBO doTextCheck(AuditTextCheckBO bo) {
        assertAuditScene(bo.getAuditScene());

        if (bo.getAuditLogId() == null) {
            throw new BusinessException(ResultCode.AUDIT_PARAM_INVALID, "auditLogId 不能为空");
        }
        if (bo.getTargetId() == null) {
            throw new BusinessException(ResultCode.AUDIT_PARAM_INVALID, "targetId 不能为空");
        }
        if (!StringUtils.hasText(bo.getContent())) {
            throw new BusinessException(ResultCode.AUDIT_PARAM_INVALID, "content 不能为空");
        }

        ContentAuditLog existing = contentAuditLogMapper.selectById(bo.getAuditLogId());
        if (existing != null && !Objects.equals(existing.getFinalResult(), AuditStatus.PENDING.getCode())) {
            return AuditCheckResultBO.builder()
                    .passed(Objects.equals(existing.getFinalResult(), AuditStatus.PASS.getCode()))
                    .pending(false)
                    .traceId(existing.getWxTraceId())
                    .build();
        }

        WxMsgSecCheckRequest wxRequest = WxMsgSecCheckRequest.builder()
                .content(bo.getContent())
                .version(2)
                .scene(bo.getScene() == null ? DEFAULT_SCENE : bo.getScene())
                .openid(bo.getOpenid())
                .build();

        WxMsgSecCheckResponse wxResponse = wxClient.msgSecCheck(wxRequest);

        String suggest = wxResponse.getResultSuggest();
        Integer label = wxResponse.getResultLabel();
        AuditStatus finalResult = mapFinalResult(suggest);

        String reason = finalResult == AuditStatus.PASS ? null
                : String.format("微信审核拒绝：suggest=%s, label=%s", suggest,
                label == null ? "unknown" : label);
        if (!resultPersistenceService.completeText(bo.getAuditLogId(), bo.getAuditScene(), bo.getTargetId(),
                wxResponse.getTraceId(), finalResult, toJson(wxResponse), reason)) {
            log.warn("[AuditService.doTextCheck] 文本审核记录未更新，可能已处理或记录不存在，auditLogId={}, targetType={}, targetId={}",
                    bo.getAuditLogId(), bo.getAuditScene().getTargetType(), bo.getTargetId());
        }

        return AuditCheckResultBO.builder()
                .passed(finalResult == AuditStatus.PASS)
                .pending(false)
                .traceId(wxResponse.getTraceId())
                .suggest(suggest)
                .label(label)
                .build();
    }

    /**
     * 文本审核完成后，同事务写入审核事实与 Outbox；业务回调由 Stream Handler 执行。
     */
    @Override
    public void processTextAudit(AuditTextCheckBO bo) {
        doTextCheck(bo);
    }

    /**
     * 多媒体审核提交。
     *
     * <p>先落库待审核记录，再写 Redis trace 绑定，保证 Redis 丢失时仍可通过数据库兜底恢复。</p>
     */
    @Override
    public AuditCheckResultBO doMediaCheck(AuditMediaCheckBO bo) {
        assertAuditScene(bo.getAuditScene());

        if (bo.getAuditLogId() == null) {
            throw new BusinessException(ResultCode.AUDIT_PARAM_INVALID, "auditLogId 不能为空");
        }
        if (bo.getTargetId() == null) {
            throw new BusinessException(ResultCode.AUDIT_PARAM_INVALID, "targetId 不能为空");
        }
        if (!StringUtils.hasText(bo.getMediaUrl())) {
            throw new BusinessException(ResultCode.AUDIT_PARAM_INVALID, "mediaUrl 不能为空");
        }

        ContentAuditLog existing = contentAuditLogMapper.selectById(bo.getAuditLogId());
        if (existing != null && StringUtils.hasText(existing.getWxTraceId())) {
            return AuditCheckResultBO.builder().pending(true).traceId(existing.getWxTraceId()).build();
        }

        WxMediaCheckAsyncRequest wxRequest = WxMediaCheckAsyncRequest.builder()
                .mediaUrl(bo.getMediaUrl())
                .mediaType(2)
                .version(2)
                .scene(bo.getScene() == null ? DEFAULT_SCENE : bo.getScene())
                .openid(bo.getOpenid())
                .build();

        WxMediaCheckAsyncResponse wxResponse = wxClient.mediaCheckAsync(wxRequest);

        int rows = contentAuditLogMapper.update(
                ContentAuditLog.builder()
                        .wxTraceId(wxResponse.getTraceId())
                        .build(),
                new LambdaUpdateWrapper<ContentAuditLog>()
                        .eq(ContentAuditLog::getId, bo.getAuditLogId())
                        .eq(ContentAuditLog::getTargetType, bo.getAuditScene().getCode())
                        .eq(ContentAuditLog::getTargetId, bo.getTargetId())
                        .eq(ContentAuditLog::getFinalResult, AuditStatus.PENDING.getCode())
        );

        if (rows <= 0) {
            log.warn("[AuditService.doMediaCheck] 媒体审核记录未绑定 traceId，可能已处理或记录不存在，auditLogId={}, auditScene={}, targetId={}, traceId={}",
                    bo.getAuditLogId(), bo.getAuditScene(), bo.getTargetId(), wxResponse.getTraceId());
        }

        bindTraceTarget(wxResponse.getTraceId(), bo.getAuditLogId(), bo.getAuditScene(), bo.getTargetId());

        return AuditCheckResultBO.builder()
                .passed(null)
                .pending(true)
                .traceId(wxResponse.getTraceId())
                .suggest(null)
                .label(null)
                .build();
    }

    /**
     * 微信多媒体审核回调。
     *
     * <p>审核结果与 Outbox 在同一事务落库，业务回调由 Stream Handler 执行。</p>
     */
    @Override
    public void handleWxMediaCallback(WxaMediaCheckMessage message) {
        if (message == null || !StringUtils.hasText(message.getTraceId())) {
            log.warn("[AuditService.handleWxMediaCallback] traceId 为空，忽略回调");
            return;
        }

        TraceTargetBinding binding = getTraceTargetBinding(message.getTraceId());
        if (binding == null || binding.getAuditLogId() == null) {
            // redis 丢失时，尝试从 db 回源（通过 traceId 查询待审核记录）
            binding = getTraceTargetBindingFromDb(message.getTraceId());
            if (binding == null) {
                log.warn("[AuditService.handleWxMediaCallback] trace 未绑定目标（redis+db 都未找到），traceId={}",
                        message.getTraceId());
                return;
            }
            log.debug("[AuditService.handleWxMediaCallback] 从db恢复trace绑定，traceId={}, auditScene={}, targetId={}",
                    message.getTraceId(), binding.getAuditScene(), binding.getTargetId());
        }

        boolean profileScene = binding.getAuditScene() == AuditScene.USER_AVATAR || binding.getAuditScene() == AuditScene.USER_BACKGROUND;
        String suggest = profileScene
                ? (message.getResult() == null ? null : message.getResult().getSuggest()) : extractSuggest(message);
        if (profileScene && (!Integer.valueOf(0).equals(message.getErrCode())
                || !("pass".equals(suggest) || "risky".equals(suggest) || "reject".equals(suggest))
                || (message.getDetail() != null && message.getDetail().stream().anyMatch(detail ->
                    detail == null || !Integer.valueOf(0).equals(detail.getErrCode())
                            || ("pass".equals(suggest) && !"pass".equals(detail.getSuggest())))))) {
            // An incomplete provider decision cannot be a definitive Profile rejection or pass.
            // Retain PENDING evidence; the requesting writer times out without changing the profile.
            return;
        }
        Integer label = extractLabel(message);
        AuditStatus finalResult = mapFinalResult(suggest);

        String reason = finalResult == AuditStatus.PASS ? null
                : String.format("微信审核拒绝：suggest=%s, label=%s", suggest,
                label == null ? "unknown" : label);
        boolean completed = resultPersistenceService.completeMedia(binding.getAuditLogId(),
                binding.getAuditScene(), binding.getTargetId(), message.getTraceId(), finalResult,
                toJson(message), reason);
        redisService.delete(RedisKeyConstant.wxAuditTrace(message.getTraceId()));
        if (!completed) {
            log.debug("[AuditService.handleWxMediaCallback] 重复或过期回调，traceId={}", message.getTraceId());
        }

        log.info("[AuditService.handleWxMediaCallback] 回调处理完成，traceId={}, auditScene={}, targetId={}, result={}",
                message.getTraceId(), binding.getAuditScene(), binding.getTargetId(), finalResult.getDesc());
    }

    /**
     * 校验目标类型。
     *
     * @param auditScene 目标类型
     * @throws BusinessException 当 targetType 不支持时抛出
     */
    private void assertAuditScene(AuditScene auditScene) {
        if (!AUDIT_ALLOWED_SCENES.contains(auditScene)) {
            throw new BusinessException(ResultCode.AUDIT_SCENE_UNSUPPORTED, "不支持的 auditScene: " + auditScene);
        }
    }

    /**
     * 根据微信审核建议值（suggest）映射本地审核结论。
     *
     * @param suggest 微信审核建议：pass/risky/reject 等
     * @return 审核结论（通过或拒绝），PASS 当 suggest=pass，否则返回 REJECT
     */
    private AuditStatus mapFinalResult(String suggest) {
        if (SUGGEST_PASS.equalsIgnoreCase(suggest)) {
            return AuditStatus.PASS;
        }
        return AuditStatus.REJECT;
    }

    /**
     * 从微信回调体中提取审核建议值（suggest）。
     * <p>优先从 result.suggest 提取，若为空则遍历 detail 列表寻找第一个非空 suggest。</p>
     *
     * @param message 微信多媒体审核回调请求
     * @return 审核建议值（pass/risky/reject 等），若无法提取则返回 null
     */
    private String extractSuggest(WxaMediaCheckMessage message) {
        if (message.getResult() != null && StringUtils.hasText(message.getResult().getSuggest())) {
            return message.getResult().getSuggest();
        }

        if (!CollectionUtils.isEmpty(message.getDetail())) {
            for (WxaMediaCheckMessage.Detail detail : message.getDetail()) {
                if (detail != null && StringUtils.hasText(detail.getSuggest())) {
                    return detail.getSuggest();
                }
            }
        }

        return null;
    }

    /**
     * 从微信回调体中提取风险标签值（label）。
     * <p>优先从 result.label 提取，若为空则遍历 detail 列表寻找第一个非空 label。</p>
     *
     * @param message 微信多媒体审核回调请求
     * @return 风险标签代码，若无法提取则返回 null
     */
    private Integer extractLabel(WxaMediaCheckMessage message) {
        if (message.getResult() != null && message.getResult().getLabel() != null) {
            return message.getResult().getLabel();
        }
        if (!CollectionUtils.isEmpty(message.getDetail())) {
            for (WxaMediaCheckMessage.Detail detail : message.getDetail()) {
                if (detail != null && detail.getLabel() != null) {
                    return detail.getLabel();
                }
            }
        }
        return null;
    }

    /**
     * 将微信 traceId 与审核目标（targetType、targetId）绑定到 Redis。
     * <p>用于异步审核回调时快速定位目标业务信息，30小时过期。</p>
     *
     * @param traceId    微信多媒体审核任务 ID
     * @param auditScene 审核目标类型
     * @param targetId   审核目标 ID
     * @throws BusinessException 当 traceId 为空时抛出
     */
    private void bindTraceTarget(String traceId, Long auditLogId, AuditScene auditScene, Long targetId) {
        if (!StringUtils.hasText(traceId)) {
            throw new BusinessException(ResultCode.WX_API_ERROR, "微信多媒体审核未返回 traceId");
        }
        TraceTargetBinding binding = new TraceTargetBinding(auditLogId, auditScene, targetId);
        redisService.set(RedisKeyConstant.wxAuditTrace(traceId), toJson(binding), RedisKeyConstant.WX_AUDIT_TRACE_TTL);
    }

    /**
     * 从 Redis 查询 traceId 绑定的审核目标信息。
     *
     * @param traceId 微信多媒体审核任务 ID
     * @return TraceTargetBinding 对象（包含 targetType、targetId），若未找到或解析失败则返回 null
     */
    private TraceTargetBinding getTraceTargetBinding(String traceId) {
        String raw = redisService.getString(RedisKeyConstant.wxAuditTrace(traceId));
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            return objectMapper.readValue(raw, TraceTargetBinding.class);
        } catch (JsonProcessingException e) {
            log.warn("[AuditService.getTraceTargetBinding] 解析 trace 绑定失败，traceId={}", traceId, e);
            redisService.delete(RedisKeyConstant.wxAuditTrace(traceId));
            return null;
        }
    }

    /**
     * 从数据库回源查询trace绑定信息。
     * 用于Redis中trace绑定丢失时的恢复机制。
     */
    private TraceTargetBinding getTraceTargetBindingFromDb(String traceId) {
        try {
            // 查询待审核状态的记录（finalResult = PENDING）
            ContentAuditLog auditLog = contentAuditLogMapper.selectOne(
                    new LambdaQueryWrapper<ContentAuditLog>()
                            .eq(ContentAuditLog::getWxTraceId, traceId)
                            .eq(ContentAuditLog::getFinalResult, AuditStatus.PENDING.getCode())
                            .last("LIMIT 1")
            );
            if (auditLog != null) {
                AuditScene auditScene = AuditScene.fromCode(auditLog.getTargetType());
                if (auditScene == null) {
                    log.warn("[AuditService.getTraceTargetBindingFromDb] 未识别的 auditSceneCode={}, traceId={}",
                            auditLog.getTargetType(), traceId);
                    return null;
                }
                return new TraceTargetBinding(auditLog.getId(), auditScene, auditLog.getTargetId());
            }
        } catch (Exception e) {
            log.warn("[AuditService.getTraceTargetBindingFromDb] 从DB查询trace绑定失败，traceId={}", traceId, e);
        }
        return null;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            log.warn("[AuditService.toJson] JSON 序列化失败", e);
            return "{}";
        }
    }

    @lombok.Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    private static class TraceTargetBinding {
        private Long auditLogId;
        private AuditScene auditScene;
        private Long targetId;
    }
}
