package cn.jualn.miniapp.module.audit.service.impl;

import cn.jualn.miniapp.common.constant.RedisKeyConstant;
import cn.jualn.miniapp.common.enums.AuditScene;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.module.audit.bo.*;
import cn.jualn.miniapp.module.audit.entity.ContentAuditLog;
import cn.jualn.miniapp.module.audit.enums.AuditStatus;
import cn.jualn.miniapp.module.audit.enums.AuditSourceEnum;
import cn.jualn.miniapp.module.audit.mapper.ContentAuditLogMapper;
import cn.jualn.miniapp.module.audit.service.AuditResultCallback;
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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditServiceImpl implements AuditService {

    private static final Integer DEFAULT_SCENE = 3;
    private static final Integer WX_RESULT_NORMAL = 0;
    private static final Integer WX_RESULT_RISK = 1;
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

    private final Map<AuditScene, AuditResultCallback> registry;
    private final WxClient wxClient;
    private final ContentAuditLogMapper contentAuditLogMapper;
    private final RedisService redisService;
    private final ObjectMapper objectMapper;

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

        int rows = contentAuditLogMapper.update(
                ContentAuditLog.builder()
                        .wxTraceId(wxResponse.getTraceId())
                        .wxResult(finalResult == AuditStatus.PASS ? WX_RESULT_NORMAL : WX_RESULT_RISK)
                        .wxDetail(toJson(wxResponse))
                        .finalResult(finalResult.getCode())
                        .build(),
                new LambdaUpdateWrapper<ContentAuditLog>()
                        .eq(ContentAuditLog::getId, bo.getAuditLogId())
                        .eq(ContentAuditLog::getTargetType, bo.getAuditScene().getCode())
                        .eq(ContentAuditLog::getTargetId, bo.getTargetId())
                        .eq(ContentAuditLog::getFinalResult, AuditStatus.PENDING.getCode())
        );

        if (rows <= 0) {
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
     * 文本审核完成后的业务回调处理。
     *
     * <p>当前不参与事务：审核记录已先行落库，业务回调失败仅记录日志，不影响审核事实本身。</p>
     */
    @Override
    public void processTextAudit(AuditTextCheckBO bo) {
        AuditCheckResultBO result = doTextCheck(bo);

        AuditResultCallback callback = registry.get(
                bo.getAuditScene()
        );
        if (callback == null) {
            log.error("[AuditService.processTextAudit] 无法找到回调处理，auditScene={}, targetId={}",
                    bo.getAuditScene(), bo.getTargetId());
            return;
        }

        try {
            if (result.getPassed()) {
                log.debug("[AuditService.processTextAudit][通过] auditScene={}, targetId={}, suggest={}",
                        bo.getAuditScene(), bo.getTargetId(), result.getSuggest());
                callback.onPass(bo.getTargetId());
            } else {
                // 拒绝原因：微信审核suggest值 + 标签
                String reason = String.format("微信审核拒绝：suggest=%s, label=%d",
                        result.getSuggest(), result.getLabel());
                log.warn("[AuditService.processTextAudit][拒绝] auditScene={}, targetId={}, reason={}",
                        bo.getAuditScene(), bo.getTargetId(), reason);
                callback.onReject(bo.getTargetId(), reason);
            }
        } catch (Exception e) {
            log.error("[AuditService.processTextAudit][回调异常] 业务回调失败，auditScene={}, targetId={}",
                    bo.getAuditScene(), bo.getTargetId(), e);
            // 回调异常不阻塞审核流程，审核日志已保存，目标业务自行查询审核结果
        }
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

        bindTraceTarget(wxResponse.getTraceId(), bo.getAuditScene(), bo.getTargetId());

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
     * <p>回调处理采用“先恢复目标绑定，再更新审核日志，再执行业务回调”的顺序，降低回调丢失的概率。</p>
     */
    @Override
    public void handleWxMediaCallback(WxaMediaCheckMessage message) {
        if (message == null || !StringUtils.hasText(message.getTraceId())) {
            log.warn("[AuditService.handleWxMediaCallback] traceId 为空，忽略回调");
            return;
        }

        TraceTargetBinding binding = getTraceTargetBinding(message.getTraceId());
        if (binding == null) {
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

        String suggest = extractSuggest(message);
        Integer label = extractLabel(message);
        AuditStatus finalResult = mapFinalResult(suggest);

        // 保存最终审核结果到db
        updateAuditLogWithResult(binding.getAuditScene().getCode(), binding.getTargetId(), message.getTraceId(),
                finalResult, toJson(message));
        redisService.delete(RedisKeyConstant.wxAuditTrace(message.getTraceId()));

        // 调用业务回调处理
        AuditResultCallback callback = registry.get(
                binding.getAuditScene()
        );
        if (callback == null) {
            log.error("[AuditService.handleWxMediaCallback] 无法找到回调处理，auditScene={}, targetId={}",
                    binding.getAuditScene(), binding.getTargetId());
            return;
        }

        try {
            if (finalResult == AuditStatus.PASS) {
                log.debug("[AuditService.handleWxMediaCallback][通过] auditScene={}, targetId={}, suggest={}",
                        binding.getAuditScene(), binding.getTargetId(), suggest);
                callback.onPass(binding.getTargetId());
            } else {
                // 拒绝原因：微信审核suggest值 + 标签
                String reason = String.format("微信审核拒绝：suggest=%s, label=%s",
                        suggest, label == null ? "unknown" : label);
                log.warn("[AuditService.handleWxMediaCallback][拒绝] auditScene={}, targetId={}, reason={}",
                        binding.getAuditScene(), binding.getTargetId(), reason);
                callback.onReject(binding.getTargetId(), reason);
            }
        } catch (Exception e) {
            log.error("[AuditService.handleWxMediaCallback][回调异常] 业务回调失败，auditScene={}, targetId={}",
                    binding.getAuditScene(), binding.getTargetId(), e);
            // 回调异常不阻塞审核结果存储，审核日志已保存
        }

        log.info("[AuditService.handleWxMediaCallback] 回调处理完成，traceId={}, auditScene={}, targetId={}, result={}",
                message.getTraceId(), binding.getAuditScene(), binding.getTargetId(), finalResult.getDesc());
    }

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
    private void bindTraceTarget(String traceId, AuditScene auditScene, Long targetId) {
        if (!StringUtils.hasText(traceId)) {
            throw new BusinessException(ResultCode.WX_API_ERROR, "微信多媒体审核未返回 traceId");
        }
        TraceTargetBinding binding = new TraceTargetBinding(auditScene, targetId);
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
     * 保存完整的审核日志记录到数据库。
     *
     * @param logRecord 审核日志对象，包含目标、结果、微信反馈等信息
     */
    private void saveAuditLog(ContentAuditLog logRecord) {
        contentAuditLogMapper.insert(logRecord);
    }

    /**
     * 在多媒体审核提交时，创建一条待审核的初始记录到数据库（双绑定策略）。
     * 当Redis过期或丢失时，回调处理可从DB中回源查询目标信息。
     */
    private void savePendingAuditLog(Integer targetType, Long targetId, String wxTraceId) {
        try {
            saveAuditLog(ContentAuditLog.builder()
                    .targetType(targetType)
                    .targetId(targetId)
                    .auditSource(AuditSourceEnum.WX_AUTO.getCode())
                    .wxTraceId(wxTraceId)
                    .finalResult(AuditStatus.PENDING.getCode())
                    .build());
            log.debug("[AuditService.savePendingAuditLog] 创建待审核记录，targetType={}, targetId={}, traceId={}",
                    targetType, targetId, wxTraceId);
        } catch (Exception e) {
            log.warn("[AuditService.savePendingAuditLog] 创建待审核记录失败（非阻塞），targetType={}, targetId={}, traceId={}",
                    targetType, targetId, wxTraceId, e);
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
                return new TraceTargetBinding(auditScene, auditLog.getTargetId());
            }
        } catch (Exception e) {
            log.warn("[AuditService.getTraceTargetBindingFromDb] 从DB查询trace绑定失败，traceId={}", traceId, e);
        }
        return null;
    }

    /**
     * 更新审核日志记录为最终结果（覆盖待审核状态）。
     * 用于微信异步回调时更新之前创建的待审核记录。
     */
    private void updateAuditLogWithResult(Integer auditScene, Long targetId, String wxTraceId,
                                          AuditStatus finalResult, String wxDetail) {
        try {
            ContentAuditLog updateLog = ContentAuditLog.builder()
                    .auditSource(AuditSourceEnum.WX_AUTO.getCode())
                    .wxTraceId(wxTraceId)
                    .wxResult(finalResult == AuditStatus.PASS ? WX_RESULT_NORMAL : WX_RESULT_RISK)
                    .wxDetail(wxDetail)
                    .finalResult(finalResult.getCode())
                    .build();

            int rows = contentAuditLogMapper.update(updateLog,
                    new LambdaUpdateWrapper<ContentAuditLog>()
                            .eq(ContentAuditLog::getTargetType, auditScene)
                            .eq(ContentAuditLog::getTargetId, targetId)
                            .eq(ContentAuditLog::getWxTraceId, wxTraceId)
                            .eq(ContentAuditLog::getFinalResult, AuditStatus.PENDING.getCode())
            );

            if (rows <= 0) {
                log.warn("[AuditService.updateAuditLogWithResult] 审核结果未更新，可能已处理或记录不存在，auditScene={}, targetId={}, traceId={}",
                        auditScene, targetId, wxTraceId);
                return;
            }

            log.debug("[AuditService.updateAuditLogWithResult] 更新审核结果，auditScene={}, targetId={}, result={}",
                    auditScene, targetId, finalResult.getDesc());
        } catch (Exception e) {
            log.warn("[AuditService.updateAuditLogWithResult] 更新审核日志失败，auditScene={}, targetId={}, traceId={}",
                    auditScene, targetId, wxTraceId, e);
        }
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
        private AuditScene auditScene;
        private Long targetId;
    }
}
