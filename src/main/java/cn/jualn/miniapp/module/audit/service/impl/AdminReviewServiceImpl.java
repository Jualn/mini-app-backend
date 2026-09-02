package cn.jualn.miniapp.module.audit.service.impl;

import cn.jualn.miniapp.common.enums.AuditScene;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.enums.UserRole;
import cn.jualn.miniapp.common.enums.UserStatus;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.module.activity.service.ActivityService;
import cn.jualn.miniapp.module.audit.bo.*;
import cn.jualn.miniapp.module.audit.entity.ContentAuditLog;
import cn.jualn.miniapp.module.audit.enums.AuditSourceEnum;
import cn.jualn.miniapp.module.audit.enums.AuditStatus;
import cn.jualn.miniapp.module.audit.mapper.AdminReviewRow;
import cn.jualn.miniapp.module.audit.mapper.AdminReviewSummaryRow;
import cn.jualn.miniapp.module.audit.mapper.ContentAuditLogMapper;
import cn.jualn.miniapp.module.audit.service.AdminReviewService;
import cn.jualn.miniapp.module.comment.service.CommentService;
import cn.jualn.miniapp.module.media.bo.MediaAttachmentBO;
import cn.jualn.miniapp.module.media.service.MediaService;
import cn.jualn.miniapp.module.post.service.PostService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.*;

@Service
@RequiredArgsConstructor
public class AdminReviewServiceImpl implements AdminReviewService {
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    private static final String SOURCE_PRE_PUBLICATION = "pre-publication";
    private static final Set<String> REJECT_REASON_CODES =
            Set.of("illegal", "abuse", "advertising", "false-info", "other");

    private final ContentAuditLogMapper contentAuditLogMapper;
    private final PostService postService;
    private final CommentService commentService;
    private final ActivityService activityService;
    private final MediaService mediaService;
    private final ObjectMapper objectMapper;

    @Override
    public AdminReviewPageBO pageReviews(AdminReviewQueryBO query) {
        AdminReviewQueryBO actual = query == null ? AdminReviewQueryBO.builder().build() : query;
        int pageSize = normalizePageSize(actual.getPageSize());
        String tab = StringUtils.hasText(actual.getTab()) ? actual.getTab() : "pending";
        String sort = StringUtils.hasText(actual.getSort()) ? actual.getSort() : "priority";
        String keyword = trimToNull(actual.getKeyword());
        Integer targetType = parseTargetType(actual.getTargetType());
        Cursor cursor = decodeCursor(actual.getCursor(), sort);

        // 当前入队条件就是微信 wx_result=1，本地统一归为高风险。
        boolean supportedRiskFilter = !StringUtils.hasText(actual.getRiskLevel())
                || "high".equals(actual.getRiskLevel());
        List<AdminReviewRow> rows = supportedRiskFilter
                ? contentAuditLogMapper.selectAdminReviewPage(
                        keyword,
                        parseId(keyword),
                        targetType,
                        tab,
                        sort,
                        cursor == null ? null : cursor.submittedAt(),
                        cursor == null ? null : cursor.taskId(),
                        pageSize + 1)
                : new ArrayList<>();
        long total = supportedRiskFilter
                ? contentAuditLogMapper.countAdminReviews(keyword, parseId(keyword), targetType, tab)
                : 0L;
        boolean hasMore = rows.size() > pageSize;
        if (hasMore) {
            rows = rows.subList(0, pageSize);
        }
        List<AdminReviewItemBO> items = rows.stream().map(this::toItemBO).toList();
        AdminReviewSummaryRow summary = contentAuditLogMapper.selectAdminReviewSummary();
        long pending = summary == null ? 0L : valueOrZero(summary.getPending());
        return AdminReviewPageBO.builder()
                .items(items)
                .total(total)
                .summary(AdminReviewPageBO.Summary.builder()
                        .pending(pending)
                        .highRisk(pending)
                        .todayCompleted(summary == null ? 0L : valueOrZero(summary.getTodayCompleted()))
                        .oldestWaitingMinutes(oldestWaitingMinutes(summary))
                        .build())
                .hasMore(hasMore)
                .nextCursor(rows.isEmpty() ? null : encodeCursor(rows.get(rows.size() - 1), sort))
                .pageSize(pageSize)
                .build();
    }

    @Override
    public AdminReviewDetailBO getReviewDetail(Long taskId) {
        if (taskId == null) {
            throw new BusinessException(ResultCode.AUDIT_PARAM_INVALID, "审核任务ID不能为空");
        }
        AdminReviewRow row = contentAuditLogMapper.selectAdminReviewDetail(taskId);
        if (row == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "审核任务不存在");
        }
        AdminReviewDetailBO detail = new AdminReviewDetailBO();
        copyItem(row, detail);
        detail.setContent(row.getContent());
        detail.setAttachments(buildAttachments(row));
        detail.setMachineAudit(buildMachineAudit(row));
        AdminReviewDetailBO.TargetContext context = new AdminReviewDetailBO.TargetContext();
        context.setTargetType(targetTypeKey(row.getTargetType()));
        context.setTargetId(row.getTargetId());
        context.setTargetStatus(row.getTargetStatus());
        context.setParentId(row.getParentId());
        detail.setTargetContext(context);
        detail.setHistory(contentAuditLogMapper
                .selectAdminReviewHistory(row.getTargetType(), row.getTargetId())
                .stream().map(this::toHistoryBO).toList());
        return detail;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void decide(AdminReviewDecisionBO command) {
        validateDecision(command);
        ContentAuditLog task = contentAuditLogMapper.selectRiskTaskForUpdate(command.getTaskId());
        if (task == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "审核任务不存在");
        }
        Long representativeId = contentAuditLogMapper.selectRepresentativeRiskTaskId(
                task.getTargetType(), task.getTargetId());
        if (!command.getTaskId().equals(representativeId)) {
            throw new BusinessException(ResultCode.DATA_CONFLICT, "请使用聚合后的审核任务ID");
        }
        ContentAuditLog idempotentDecision =
                contentAuditLogMapper.selectManualDecisionByIdempotencyKey(command.getIdempotencyKey());
        if (idempotentDecision != null) {
            assertSameIdempotentDecision(task, command, idempotentDecision);
            return;
        }
        if (contentAuditLogMapper.countPendingAutomaticAudits(task.getTargetType(), task.getTargetId()) > 0) {
            throw new BusinessException(ResultCode.DATA_CONFLICT, "机器审核尚未全部完成");
        }
        // RISK: 当前表无审核轮次字段，基础版按每个目标只有一次人工结论处理。
        if (contentAuditLogMapper.countManualDecisions(task.getTargetType(), task.getTargetId()) > 0) {
            throw new BusinessException(ResultCode.DATA_CONFLICT, "审核任务已处理");
        }

        boolean approve = "approve".equals(command.getAction());
        String decisionReason = decisionReason(command);
        applyTargetDecision(task, command.getOperatorId(), approve, decisionReason);

        ContentAuditLog manualLog = ContentAuditLog.builder()
                .targetType(task.getTargetType())
                .targetId(task.getTargetId())
                .auditSource(AuditSourceEnum.ADMIN_MANUAL.getCode())
                .adminUserId(command.getOperatorId())
                .adminAction(approve ? AuditStatus.PASS.getCode() : AuditStatus.REJECT.getCode())
                .adminRemark(decisionReason)
                .idempotencyKey(command.getIdempotencyKey())
                .finalResult(approve ? AuditStatus.PASS.getCode() : AuditStatus.REJECT.getCode())
                .build();
        contentAuditLogMapper.insert(manualLog);
    }

    private void applyTargetDecision(
            ContentAuditLog task,
            Long operatorId,
            boolean approve,
            String decisionReason) {
        AuditScene scene = AuditScene.fromCode(task.getTargetType());
        if (scene == AuditScene.POST) {
            if (approve) postService.approvePostReview(task.getTargetId(), operatorId, decisionReason);
            else postService.rejectPostReview(task.getTargetId(), operatorId, decisionReason);
            return;
        }
        if (scene == AuditScene.COMMENT) {
            if (approve) commentService.approveCommentReview(task.getTargetId(), operatorId, decisionReason);
            else commentService.rejectCommentReview(task.getTargetId(), operatorId, decisionReason);
            return;
        }
        if (scene == AuditScene.ACTIVITY) {
            if (approve) activityService.approveActivityReview(task.getTargetId(), operatorId, decisionReason);
            else activityService.rejectActivityReview(task.getTargetId(), operatorId, decisionReason);
            return;
        }
        throw new BusinessException(ResultCode.AUDIT_SCENE_UNSUPPORTED, "该类型暂未开启人工复核");
    }

    private void validateDecision(AdminReviewDecisionBO command) {
        if (command == null || command.getTaskId() == null || command.getOperatorId() == null
                || !StringUtils.hasText(command.getIdempotencyKey())
                || !Set.of("approve", "reject").contains(command.getAction())) {
            throw new BusinessException(ResultCode.AUDIT_PARAM_INVALID, "人工审核参数不完整");
        }
        if ("reject".equals(command.getAction())
                && (!StringUtils.hasText(command.getReasonCode())
                || !REJECT_REASON_CODES.contains(command.getReasonCode()))) {
            throw new BusinessException(ResultCode.AUDIT_PARAM_INVALID, "拒绝时必须选择有效原因");
        }
    }

    private void assertSameIdempotentDecision(
            ContentAuditLog task,
            AdminReviewDecisionBO command,
            ContentAuditLog existing) {
        boolean approve = "approve".equals(command.getAction());
        int action = approve ? AuditStatus.PASS.getCode() : AuditStatus.REJECT.getCode();
        if (!Objects.equals(existing.getTargetType(), task.getTargetType())
                || !Objects.equals(existing.getTargetId(), task.getTargetId())
                || !Objects.equals(existing.getAdminUserId(), command.getOperatorId())
                || !Objects.equals(existing.getAdminAction(), action)
                || !Objects.equals(existing.getAdminRemark(), decisionReason(command))) {
            throw new BusinessException(ResultCode.DATA_CONFLICT, "幂等键已被其他审核命令使用");
        }
    }

    private String decisionReason(AdminReviewDecisionBO command) {
        String remark = trimToNull(command.getRemark());
        if ("approve".equals(command.getAction())) {
            return remark;
        }
        return remark == null ? command.getReasonCode() : command.getReasonCode() + ": " + remark;
    }

    private AdminReviewItemBO toItemBO(AdminReviewRow row) {
        AdminReviewItemBO item = new AdminReviewItemBO();
        copyItem(row, item);
        return item;
    }

    private void copyItem(AdminReviewRow row, AdminReviewItemBO item) {
        MachineFacts facts = parseMachineFacts(row.getWxDetail());
        item.setTaskId(row.getTaskId());
        item.setTargetId(row.getTargetId());
        item.setTargetType(targetTypeKey(row.getTargetType()));
        item.setTitle(row.getTitle());
        item.setContentPreview(preview(row.getContent()));
        AdminReviewItemBO.Author author = new AdminReviewItemBO.Author();
        author.setId(row.getAuthorId());
        author.setNickname(row.getAuthorName());
        author.setAvatarUrl(row.getAvatarUrl());
        author.setRoleCode(UserRole.fromCode(row.getAuthorRole()).name().toLowerCase());
        author.setAccountStatus(UserStatus.fromCode(row.getAuthorStatus()).name().toLowerCase());
        author.setJoinedAt(row.getAuthorCreatedAt());
        item.setAuthor(author);
        item.setRiskLevel("high");
        item.setRiskScore(facts.riskScore());
        item.setMachineConclusion(machineConclusion(facts));
        item.setMatchedLabels(facts.labels());
        item.setSourceType(SOURCE_PRE_PUBLICATION);
        item.setSubmittedAt(row.getSubmittedAt());
        item.setReportCount(valueOrZero(row.getReportCount()));
        item.setAttachmentCount(valueOrZero(row.getAttachmentCount()));
        item.setStatus(reviewStatus(row.getManualResult()));
    }

    private List<AdminReviewDetailBO.Attachment> buildAttachments(AdminReviewRow row) {
        if (row.getTargetType() == AuditScene.COMMENT.getCode()) {
            if (!StringUtils.hasText(row.getImageUrl())) return List.of();
            AdminReviewDetailBO.Attachment attachment = new AdminReviewDetailBO.Attachment();
            attachment.setId(row.getTargetId());
            attachment.setType("image");
            attachment.setName("评论图片");
            attachment.setUrl(row.getImageUrl());
            return List.of(attachment);
        }
        TargetType targetType = row.getTargetType() == AuditScene.POST.getCode()
                ? TargetType.POST : TargetType.ACTIVITY;
        return mediaService.listAttachments(targetType, row.getTargetId()).stream()
                .map(this::toAttachmentBO).toList();
    }

    private AdminReviewDetailBO.Attachment toAttachmentBO(MediaAttachmentBO source) {
        AdminReviewDetailBO.Attachment target = new AdminReviewDetailBO.Attachment();
        target.setId(source.getId());
        target.setType(source.getType() == null ? "unknown" : source.getType().name().toLowerCase());
        target.setName(StringUtils.hasText(source.getOriginalName())
                ? source.getOriginalName() : "附件 " + source.getId());
        target.setUrl(source.getUrl());
        return target;
    }

    private AdminReviewDetailBO.MachineAudit buildMachineAudit(AdminReviewRow row) {
        MachineFacts facts = parseMachineFacts(row.getWxDetail());
        AdminReviewDetailBO.MachineAudit audit = new AdminReviewDetailBO.MachineAudit();
        audit.setTraceId(row.getWxTraceId());
        audit.setProvider("wechat-content-security");
        audit.setSuggest(facts.suggest());
        audit.setRiskScore(facts.riskScore());
        audit.setMatchedLabels(facts.labels());
        return audit;
    }

    private AdminReviewDetailBO.HistoryEntry toHistoryBO(ContentAuditLog log) {
        AdminReviewDetailBO.HistoryEntry item = new AdminReviewDetailBO.HistoryEntry();
        item.setId(log.getId());
        boolean manual = Objects.equals(log.getAuditSource(), AuditSourceEnum.ADMIN_MANUAL.getCode());
        item.setEventType(manual ? "manual-decision" : "machine-audit");
        item.setSource(manual ? "admin" : "wechat-content-security");
        item.setResult(reviewResult(log.getFinalResult()));
        item.setReason(manual ? log.getAdminRemark() : machineConclusion(parseMachineFacts(log.getWxDetail())));
        item.setOperatorId(log.getAdminUserId());
        item.setCreatedAt(log.getCreatedAt());
        return item;
    }

    private MachineFacts parseMachineFacts(String detail) {
        if (!StringUtils.hasText(detail)) {
            return new MachineFacts(null, null, List.of());
        }
        try {
            JsonNode root = objectMapper.readTree(detail);
            MachineAccumulator accumulator = new MachineAccumulator();
            collectMachineFacts(root, accumulator);
            Integer score = accumulator.maxProbability == null
                    ? null : normalizeScore(accumulator.maxProbability);
            return new MachineFacts(accumulator.suggest, score, List.copyOf(accumulator.labels));
        } catch (Exception ignored) {
            return new MachineFacts(null, null, List.of());
        }
    }

    private void collectMachineFacts(JsonNode node, MachineAccumulator accumulator) {
        if (node == null) return;
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                String name = entry.getKey();
                JsonNode value = entry.getValue();
                if ("suggest".equals(name) && accumulator.suggest == null && value.isTextual()) {
                    accumulator.suggest = value.asText();
                } else if ("label".equals(name) && (value.isInt() || value.isLong() || value.isTextual())) {
                    accumulator.labels.add("wx-label-" + value.asText());
                } else if ("prob".equals(name) && value.isNumber()) {
                    double probability = value.asDouble();
                    accumulator.maxProbability = accumulator.maxProbability == null
                            ? probability : Math.max(accumulator.maxProbability, probability);
                }
                collectMachineFacts(value, accumulator);
            });
        } else if (node.isArray()) {
            node.forEach(child -> collectMachineFacts(child, accumulator));
        }
    }

    private int normalizeScore(double probability) {
        double value = probability <= 1D ? probability * 100D : probability;
        return (int) Math.round(Math.max(0D, Math.min(100D, value)));
    }

    private String machineConclusion(MachineFacts facts) {
        return StringUtils.hasText(facts.suggest())
                ? "wechat-" + facts.suggest() : "wechat-risk";
    }

    private String reviewStatus(Integer manualResult) {
        if (Objects.equals(manualResult, AuditStatus.PASS.getCode())) return "approved";
        if (Objects.equals(manualResult, AuditStatus.REJECT.getCode())) return "rejected";
        return "pending";
    }

    private String reviewResult(Integer result) {
        if (Objects.equals(result, AuditStatus.PASS.getCode())) return "approved";
        if (Objects.equals(result, AuditStatus.REJECT.getCode())) return "rejected";
        return "pending";
    }

    private String targetTypeKey(Integer targetType) {
        AuditScene scene = AuditScene.fromCode(targetType);
        return scene == null ? "unknown" : scene.getKey();
    }

    private Integer parseTargetType(String value) {
        if (!StringUtils.hasText(value)) return null;
        for (AuditScene scene : List.of(AuditScene.POST, AuditScene.ACTIVITY, AuditScene.EXAM, AuditScene.COMMENT)) {
            if (scene.getKey().equals(value)) return scene.getCode();
        }
        throw new BusinessException(ResultCode.AUDIT_SCENE_UNSUPPORTED);
    }

    private int normalizePageSize(Integer pageSize) {
        return pageSize == null ? DEFAULT_PAGE_SIZE : Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
    }

    private String preview(String content) {
        if (content == null) return "";
        String normalized = content.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 160 ? normalized : normalized.substring(0, 160) + "...";
    }

    private long oldestWaitingMinutes(AdminReviewSummaryRow summary) {
        if (summary == null || summary.getOldestPendingAt() == null) return 0L;
        return Math.max(0L, Duration.between(summary.getOldestPendingAt(), LocalDateTime.now()).toMinutes());
    }

    private String encodeCursor(AdminReviewRow row, String sort) {
        String raw = sort + "|" + row.getSubmittedAt() + "|" + row.getTaskId();
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private Cursor decodeCursor(String encoded, String expectedSort) {
        if (!StringUtils.hasText(encoded)) return null;
        try {
            String raw = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            String[] parts = raw.split("\\|", -1);
            if (parts.length != 3 || !expectedSort.equals(parts[0])) {
                throw new IllegalArgumentException("cursor mismatch");
            }
            return new Cursor(LocalDateTime.parse(parts[1]), Long.parseLong(parts[2]));
        } catch (IllegalArgumentException | DateTimeParseException e) {
            throw new BusinessException(ResultCode.AUDIT_PARAM_INVALID, "分页游标无效或与排序不匹配");
        }
    }

    private Long parseId(String value) {
        if (!StringUtils.hasText(value)) return null;
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private long valueOrZero(Long value) {
        return value == null ? 0L : value;
    }

    private record Cursor(LocalDateTime submittedAt, Long taskId) {
    }

    private record MachineFacts(String suggest, Integer riskScore, List<String> labels) {
    }

    private static class MachineAccumulator {
        private String suggest;
        private Double maxProbability;
        private final Set<String> labels = new LinkedHashSet<>();
    }
}
