package cn.jualn.miniapp.module.audit.service.impl;

import cn.jualn.miniapp.module.audit.enums.AuditScene;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.module.user.enums.UserRole;
import cn.jualn.miniapp.module.user.enums.UserStatus;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.module.authorization.model.RbacModels;
import cn.jualn.miniapp.module.activity.service.ActivityReviewService;
import cn.jualn.miniapp.module.audit.bo.*;
import cn.jualn.miniapp.module.audit.entity.ContentAuditLog;
import cn.jualn.miniapp.module.audit.enums.AuditSourceEnum;
import cn.jualn.miniapp.module.audit.enums.AuditStatus;
import cn.jualn.miniapp.module.audit.mapper.AdminReviewRow;
import cn.jualn.miniapp.module.audit.mapper.AdminReviewSummaryRow;
import cn.jualn.miniapp.module.audit.mapper.ContentAuditLogMapper;
import cn.jualn.miniapp.module.audit.service.AdminReviewService;
import cn.jualn.miniapp.module.comment.service.CommentGovernanceService;
import cn.jualn.miniapp.module.media.bo.MediaAttachmentBO;
import cn.jualn.miniapp.module.media.service.MediaQueries;
import cn.jualn.miniapp.module.post.service.PostGovernanceService;
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
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.beans.factory.ObjectProvider<cn.jualn.miniapp.module.media.service.MediaDeliveryUrls> deliveryUrls;
    private String previewUrl(String url){return url!=null && url.contains(cn.jualn.miniapp.module.media.service.MediaDeliveryUrls.PATH)?deliveryUrls.getObject().adminVariant(url):url;}

    @org.springframework.beans.factory.annotation.Autowired
    private cn.jualn.miniapp.module.admin.operation.service.AdminAuditJournal managementAudit;

    private final cn.jualn.miniapp.module.authorization.service.CurrentAuthorization authorization;
    @org.springframework.beans.factory.annotation.Autowired private cn.jualn.miniapp.module.content.service.GrowthManagementTargets growth;
    @org.springframework.beans.factory.annotation.Autowired private cn.jualn.miniapp.module.media.service.GrowthMediaService growthMedia;
    @org.springframework.beans.factory.annotation.Autowired
    private cn.jualn.miniapp.module.audit.service.ContentSafetyReviewService reviews;
    @org.springframework.beans.factory.annotation.Autowired
    private cn.jualn.miniapp.module.audit.service.ContentSafetyAuthorization reviewAccess;
    private void authorizeReview(long actor,Integer sceneCode,Long id,String permission) {
        AuditScene scene=AuditScene.fromCode(sceneCode);
        if(scene!=AuditScene.POST && scene!=AuditScene.COMMENT && scene!=AuditScene.ACTIVITY) throw new BusinessException(ResultCode.AUDIT_SCENE_UNSUPPORTED);
        String space=scene==AuditScene.COMMENT?commentService.managementNamespace(id):cn.jualn.miniapp.module.authorization.service.CurrentAuthorization.namespace(scene.getTargetType());
        if(!growth.require(scene==AuditScene.POST?"post":"comment",id,actor,"POST_SAFETY_REVIEW","review:read".equals(permission)?new String[]{"review:read"}:new String[]{"review:read","review:decide"}))reviewAccess.require(actor,space,"review:decide".equals(permission),true);
    }

    private void authorizeRound(cn.jualn.miniapp.module.audit.entity.ContentSafetyReview r,long actor,boolean decide) {
        if(!growth.require(r.getTargetType()==1?"post":"comment",r.getTargetId(),actor,"POST_SAFETY_REVIEW",decide?new String[]{"review:read","review:decide"}:new String[]{"review:read"})) reviewAccess.require(actor,r.getResourceNamespace(),decide,false);
    }

    @org.springframework.beans.factory.annotation.Autowired private cn.jualn.miniapp.module.post.service.GrowthRecordAccess growthAccess;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    private static final String SOURCE_PRE_PUBLICATION = "pre-publication";
    private static final Set<String> REJECT_REASON_CODES =
            Set.of("illegal", "abuse", "advertising", "false-info", "other");

    private final ContentAuditLogMapper contentAuditLogMapper;
    private final PostGovernanceService postService;
    private final CommentGovernanceService commentService;
    private final ActivityReviewService activityService;
    private final MediaQueries mediaService;
    private final ObjectMapper objectMapper;

    @Override
    public AdminReviewPageBO pageReviews(AdminReviewQueryBO query) {
        boolean growthQuery=query!=null && cn.jualn.miniapp.module.post.service.GrowthRecordAccess.growthFilter(query.getGrowthType(),query.getGrowthOrganizationId());
        long growthActor=cn.jualn.miniapp.common.security.AdminStpUtil.STP_LOGIC.getLoginIdAsLong();
        var growthOrgs=growthQuery?growthAccess.organizations(growthActor,"POST_SAFETY_REVIEW","review:read"):List.<String>of();
        if(growthQuery && (growthOrgs.isEmpty() || query.getGrowthOrganizationId()!=null && !growthOrgs.contains(query.getGrowthOrganizationId())))throw cn.jualn.miniapp.module.post.GrowthProblems.missing();
        if(growthQuery && query.getGrowthType()!=null && !Set.of("ALL","GROWTH","POLITICAL_BIRTHDAY").contains(query.getGrowthType()))throw cn.jualn.miniapp.module.post.PostProblems.invalid("growthType","INVALID_VALUE","Invalid growth type");
        List<String> namespaces = growthQuery?List.of("POST"):readableNamespaces();

        AdminReviewQueryBO actual = query == null ? AdminReviewQueryBO.builder().build() : query;
        int pageSize = normalizePageSize(actual.getPageSize());
        String tab = StringUtils.hasText(actual.getTab()) ? actual.getTab() : "pending";
        String sort = StringUtils.hasText(actual.getSort()) ? actual.getSort() : "priority";
        String keyword = trimToNull(actual.getKeyword());
        Integer targetType = parseTargetType(actual.getTargetType());
        String cursorContext=sort+":"+cn.jualn.miniapp.module.article.service.ArticleCursor.fingerprint(Arrays.asList(growthActor,actual.getKeyword(),actual.getTargetType(),actual.getRiskLevel(),actual.getTab(),actual.getGrowthType(),actual.getGrowthOrganizationId()));
        Cursor cursor = decodeCursor(actual.getCursor(), cursorContext);

        // 当前入队条件就是微信 wx_result=1，本地统一归为高风险。
        boolean supportedRiskFilter = !StringUtils.hasText(actual.getRiskLevel())
                || "high".equals(actual.getRiskLevel());
        List<AdminReviewRow> rows = supportedRiskFilter
                ? (growthQuery?contentAuditLogMapper.selectGrowthReviewPage(keyword,parseId(keyword),targetType,tab,sort,cursor==null?null:cursor.submittedAt(),cursor==null?null:cursor.taskId(),pageSize+1,namespaces,actual.getGrowthType(),actual.getGrowthOrganizationId(),growthOrgs):contentAuditLogMapper.selectAdminReviewPage(
                        keyword,
                        parseId(keyword),
                        targetType,
                        tab,
                        sort,
                        cursor == null ? null : cursor.submittedAt(),
                        cursor == null ? null : cursor.taskId(),
                        pageSize + 1, namespaces))
                : new ArrayList<>();
        long total = supportedRiskFilter
                ? (growthQuery?contentAuditLogMapper.countGrowthReviews(keyword,parseId(keyword),targetType,tab,namespaces,actual.getGrowthType(),actual.getGrowthOrganizationId(),growthOrgs):contentAuditLogMapper.countAdminReviews(keyword, parseId(keyword), targetType, tab, namespaces))
                : 0L;
        boolean hasMore = rows.size() > pageSize;
        if (hasMore) {
            rows = rows.subList(0, pageSize);
        }
        List<AdminReviewItemBO> items = rows.stream().map(this::toItemBO).toList();
        var locations=growth.locations(items.stream().map(i->new cn.jualn.miniapp.module.content.bo.ManagementTarget.Reference(i.getTargetType(),i.getTargetId())).toList());
        for(var item:items)item.setManagementTarget(locations.get(item.getTargetType()+":"+item.getTargetId()));
        AdminReviewSummaryRow summary = growthQuery ? contentAuditLogMapper.selectGrowthReviewSummary(namespaces,actual.getGrowthType(),actual.getGrowthOrganizationId(),growthOrgs) : namespaces == null ? contentAuditLogMapper.selectAdminReviewSummary()
                : contentAuditLogMapper.selectScopedReviewSummary(namespaces);
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
                .nextCursor(rows.isEmpty() ? null : encodeCursor(rows.get(rows.size() - 1), cursorContext))
                .pageSize(pageSize)
                .build();
    }

    private List<String> readableNamespaces() {
        long actor=cn.jualn.miniapp.common.security.AdminStpUtil.STP_LOGIC.getLoginIdAsLong();
        return reviewAccess.readable(actor);
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
        authorizeReview(cn.jualn.miniapp.common.security.AdminStpUtil.STP_LOGIC.getLoginIdAsLong(),row.getTargetType(),row.getTargetId(),"review:read");
        AdminReviewDetailBO detail = new AdminReviewDetailBO();
        copyItem(row, detail);
        detail.setManagementTarget(growth.location(targetTypeKey(row.getTargetType()),row.getTargetId()));
        var round=reviews.find(taskId);
        if(round!=null) {
            authorizeRound(round,cn.jualn.miniapp.common.security.AdminStpUtil.STP_LOGIC.getLoginIdAsLong(),false);
            detail.setQaContext(reviews.qaContext(round));
            if(round.getGrowthOrganizationId()!=null){String state=switch(round.getState()){case "approved"->"PASSED";case "rejected"->"REJECTED";case "pending"->"REVIEW_REQUIRED";case "check-failed"->"CHECK_FAILED";default->"CHECKING";};detail.setGrowthMedia(growthMedia.evidence(reviews.attachments(round).stream().map(AdminReviewDetailBO.Attachment::getUrl).toList(),state));}
            detail.setContent(round.getSnapshotContent());detail.setAttachments(reviews.attachments(round).stream().map(a->{a.setUrl(previewUrl(a.getUrl()));return a;}).toList());detail.setEvidence(reviews.evidence(round));
        } else {detail.setContent("");detail.setAttachments(List.of());}

        if(detail.getGrowthRecord()!=null)detail.setAttachments(List.of());
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
    @Transactional(rollbackFor = Exception.class, isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void decide(AdminReviewDecisionBO command) {
        validateDecision(command);
        var round=reviews.find(command.getTaskId());
        if(round!=null){authorizeRound(round,command.getOperatorId(),true);if(contentAuditLogMapper.selectManualDecisionByIdempotencyKey(command.getIdempotencyKey())==null)reviews.guardMentorPublication(command.getTaskId(),command.getOperatorId());}
        ContentAuditLog task;
        if(round==null) {
            task=contentAuditLogMapper.selectRiskTaskForUpdate(command.getTaskId());
            if(task==null) throw new BusinessException(ResultCode.NOT_FOUND,"审核任务不存在");
            authorizeReview(command.getOperatorId(),task.getTargetType(),task.getTargetId(),"review:decide");
        } else {
            authorizeRound(round,command.getOperatorId(),true);
            task=ContentAuditLog.builder().targetType(round.getTargetType()).targetId(round.getTargetId()).reviewTaskId(round.getTaskId()).build();
        }
        var prior=contentAuditLogMapper.selectManualDecisionByIdempotencyKey(command.getIdempotencyKey());
        if(prior!=null) {
            if(round==null && !Objects.equals(command.getTaskId(),contentAuditLogMapper.selectRepresentativeRiskTaskId(task.getTargetType(),task.getTargetId())))
                throw new cn.jualn.miniapp.module.audit.service.ReviewConflict("review-legacy-unresolved");
            assertSameIdempotentDecision(task,command,prior);return;
        }
        if(round==null) throw new cn.jualn.miniapp.module.audit.service.ReviewConflict("review-legacy-unresolved");
        String inactive=reviews.invalidation(round,true);
        round=reviews.lock(round.getTaskId());
        // Re-read receipt after waiting on the target/round lock.
        prior=contentAuditLogMapper.selectManualDecisionByIdempotencyKey(command.getIdempotencyKey());
        if(prior!=null) {assertSameIdempotentDecision(task,command,prior);return;}
        managementAudit.confirmed(command.getOperatorId(),"decideAdminSafetyReview","SAFETY_REVIEW",round.getTaskId().toString());
        if(Set.of("approved","rejected").contains(round.getState())) throw new cn.jualn.miniapp.module.audit.service.ReviewConflict("review-already-decided");
        if("superseded".equals(round.getState()) || "target-changed".equals(inactive)) throw new cn.jualn.miniapp.module.audit.service.ReviewConflict("review-version-stale");
        if(inactive!=null || Set.of("cancelled","invalidated").contains(round.getState())) throw new cn.jualn.miniapp.module.audit.service.ReviewConflict("review-target-inactive");
        var evidence=reviews.evidence(round);
        if(!evidence.complete()) throw new cn.jualn.miniapp.module.audit.service.ReviewConflict("review-evidence-incomplete");
        if(!"pending".equals(round.getState()) || cn.jualn.miniapp.module.audit.service.ContentSafetyReviewService.activeChecks(evidence).stream().anyMatch(c->!Set.of("pass","review").contains(c.result())))
            throw new cn.jualn.miniapp.module.audit.service.ReviewConflict("review-not-decidable");
        boolean approve="approve".equals(command.getAction());
        String reason=decisionReason(command);
        applyTargetDecision(task,command.getOperatorId(),approve,reason);
        contentAuditLogMapper.insert(ContentAuditLog.builder().targetType(task.getTargetType()).targetId(task.getTargetId())
            .reviewTaskId(round.getTaskId()).auditSource(AuditSourceEnum.ADMIN_MANUAL.getCode()).adminUserId(command.getOperatorId())
            .adminAction(approve?1:2).adminRemark(reason).idempotencyKey(command.getIdempotencyKey()).finalResult(approve?1:2).build());
        reviews.finishManual(round,approve);
        managementAudit.operation(java.util.UUID.randomUUID().toString(),command.getOperatorId(),"decideAdminSafetyReview","SAFETY_REVIEW",String.valueOf(round.getTaskId()),java.time.LocalDateTime.now(java.time.ZoneOffset.UTC));
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
        if (!Objects.equals(existing.getReviewTaskId(),task.getReviewTaskId())
                || !Objects.equals(existing.getTargetType(), task.getTargetType())
                || !Objects.equals(existing.getTargetId(), task.getTargetId())
                || !Objects.equals(existing.getAdminUserId(), command.getOperatorId())
                || !Objects.equals(existing.getAdminAction(), action)
                || !Objects.equals(existing.getAdminRemark(), decisionReason(command))) {
            throw new cn.jualn.miniapp.module.audit.service.ReviewConflict("review-idempotency-conflict");
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
        var round=reviews.find(row.getTaskId());
        if(round!=null) item.setReview(reviews.projection(round));
        item.setGrowthRecord(growth.link(row.getTargetType()==1?"post":"comment",row.getTargetId()));
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
        item.setStatus(round==null?reviewStatus(row.getManualResult()):Set.of("approved","rejected").contains(round.getState())?round.getState():"pending");
    }

    private List<AdminReviewDetailBO.Attachment> buildAttachments(AdminReviewRow row) {
        if (row.getTargetType() == AuditScene.COMMENT.getCode()) {
            if (!StringUtils.hasText(row.getImageUrl())) return List.of();
            AdminReviewDetailBO.Attachment attachment = new AdminReviewDetailBO.Attachment();
            attachment.setId(row.getTargetId());
            attachment.setType("image");
            attachment.setName("评论图片");
            attachment.setUrl(previewUrl(row.getImageUrl()));
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
        target.setUrl(previewUrl(source.getUrl()));
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
        if(log.getReviewTaskId()!=null) {var r=reviews.find(log.getReviewTaskId());if(r!=null) {item.setTaskId(r.getTaskId().toString());item.setSubmissionVersion(r.getSubmissionVersion());}}

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
        return Math.max(0L, Duration.between(summary.getOldestPendingAt(), LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"))).toMinutes());
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
