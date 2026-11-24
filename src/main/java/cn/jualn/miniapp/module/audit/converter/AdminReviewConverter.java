package cn.jualn.miniapp.module.audit.converter;

import cn.jualn.miniapp.module.audit.bo.*;
import cn.jualn.miniapp.module.audit.dto.admin.AdminReviewDecisionRequest;
import cn.jualn.miniapp.module.audit.dto.admin.AdminReviewPageQuery;
import cn.jualn.miniapp.module.audit.vo.admin.AdminReviewDetailVO;
import cn.jualn.miniapp.module.audit.vo.admin.AdminReviewItemVO;
import cn.jualn.miniapp.module.audit.vo.admin.AdminReviewPageVO;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

@Component
public class AdminReviewConverter {

    public AdminReviewQueryBO toQueryBO(AdminReviewPageQuery query) {
        return AdminReviewQueryBO.builder()
                .keyword(query.getKeyword()).growthType(query.getGrowthType()).growthOrganizationId(query.getGrowthOrganizationId())
                .targetType(query.getTargetType())
                .riskLevel(query.getRiskLevel())
                .tab(query.getTab())
                .sort(query.getSort())
                .cursor(query.getCursor())
                .pageSize(query.getPageSize())
                .build();
    }

    public AdminReviewDecisionBO toDecisionBO(
            Long taskId,
            Long operatorId,
            String idempotencyKey,
            AdminReviewDecisionRequest request) {
        return AdminReviewDecisionBO.builder()
                .taskId(taskId)
                .operatorId(operatorId)
                .idempotencyKey(idempotencyKey)
                .action(request.getAction())
                .reasonCode(request.getReasonCode())
                .remark(request.getRemark())
                .build();
    }

    public AdminReviewPageVO toPageVO(AdminReviewPageBO source) {
        AdminReviewPageVO target = new AdminReviewPageVO();
        target.setItems(source.getItems().stream().map(this::toItemVO).toList());
        target.setTotal(source.getTotal());
        target.setHasMore(source.getHasMore());
        target.setNextCursor(source.getNextCursor());
        target.setPageSize(source.getPageSize());
        AdminReviewPageVO.Summary summary = new AdminReviewPageVO.Summary();
        summary.setPending(source.getSummary().getPending());
        summary.setHighRisk(source.getSummary().getHighRisk());
        summary.setTodayCompleted(source.getSummary().getTodayCompleted());
        summary.setOldestWaitingMinutes(source.getSummary().getOldestWaitingMinutes());
        target.setSummary(summary);
        return target;
    }

    public AdminReviewDetailVO toDetailVO(AdminReviewDetailBO source) {
        AdminReviewDetailVO target = new AdminReviewDetailVO();
        copyItem(source, target);
        target.setGrowthMedia(source.getGrowthMedia());
        target.setContent(source.getContent());
        target.setQaContext(source.getQaContext());
        if(source.getEvidence()!=null) {
            var e=source.getEvidence();
            target.setEvidence(new cn.jualn.miniapp.module.audit.vo.admin.SafetyReviewProjection.Evidence(e.submissionVersion(),e.complete(),e.checks().stream().map(c->new cn.jualn.miniapp.module.audit.vo.admin.SafetyReviewProjection.Check(c.checkId(),c.inputReference(),c.required(),c.executionState(),c.result(),c.provider(),c.traceId(),c.providerFacts()==null?null:new cn.jualn.miniapp.module.audit.vo.admin.SafetyReviewProjection.Facts(c.providerFacts().resultCode(),c.providerFacts().suggestion(),c.providerFacts().matchedLabels(),c.providerFacts().detail()),c.supersedesCheckId())).toList()));
        }

        target.setAttachments(source.getAttachments().stream().map(item -> {
            AdminReviewDetailVO.Attachment vo = new AdminReviewDetailVO.Attachment();
            vo.setId(stringValue(item.getId()));
            vo.setType(item.getType());
            vo.setName(item.getName());
            vo.setUrl(item.getUrl());
            return vo;
        }).toList());
        AdminReviewDetailVO.MachineAudit machine = new AdminReviewDetailVO.MachineAudit();
        machine.setTraceId(source.getMachineAudit().getTraceId());
        machine.setProvider(source.getMachineAudit().getProvider());
        machine.setSuggest(source.getMachineAudit().getSuggest());
        machine.setRiskScore(source.getMachineAudit().getRiskScore());
        machine.setMatchedLabels(source.getMachineAudit().getMatchedLabels());
        target.setMachineAudit(machine);
        AdminReviewDetailVO.TargetContext context = new AdminReviewDetailVO.TargetContext();
        context.setTargetType(source.getTargetContext().getTargetType());
        context.setTargetId(stringValue(source.getTargetContext().getTargetId()));
        context.setTargetStatus(source.getTargetContext().getTargetStatus());
        context.setParentId(stringValue(source.getTargetContext().getParentId()));
        target.setTargetContext(context);
        target.setHistory(source.getHistory().stream().map(item -> {
            AdminReviewDetailVO.HistoryEntry vo = new AdminReviewDetailVO.HistoryEntry();
            vo.setId(stringValue(item.getId()));
            vo.setTaskId(item.getTaskId());vo.setSubmissionVersion(item.getSubmissionVersion());
            vo.setEventType(item.getEventType());
            vo.setSource(item.getSource());
            vo.setResult(item.getResult());
            vo.setReason(item.getReason());
            vo.setOperatorId(stringValue(item.getOperatorId()));
            vo.setCreatedAt(item.getCreatedAt());
            return vo;
        }).toList());
        return target;
    }

    private AdminReviewItemVO toItemVO(AdminReviewItemBO source) {
        AdminReviewItemVO target = new AdminReviewItemVO();
        copyItem(source, target);
        return target;
    }

    private void copyItem(AdminReviewItemBO source, AdminReviewItemVO target) {
        target.setGrowthRecord(source.getGrowthRecord());
        var location=source.getManagementTarget();
        if(location!=null)target.setManagementTarget(new AdminReviewItemVO.ManagementTarget(location.namespace(),new AdminReviewItemVO.BusinessTarget(location.businessTarget().type(),location.businessTarget().organizationId())));
        if(source.getReview()!=null) {var r=source.getReview();target.setReview(new cn.jualn.miniapp.module.audit.vo.admin.SafetyReviewProjection.Round(r.submissionVersion(),r.scene(),r.namespace(),new cn.jualn.miniapp.module.audit.vo.admin.SafetyReviewProjection.BusinessTarget(r.businessTarget().type()),r.submissionKind(),r.state(),r.previousTaskId(),r.supersededByTaskId(),r.decisionOrigin(),r.invalidationReason()));}
        target.setTaskId(stringValue(source.getTaskId()));
        target.setTargetId(stringValue(source.getTargetId()));
        target.setTargetType(source.getTargetType());
        target.setTitle(source.getTitle());
        target.setContentPreview(source.getContentPreview());
        target.setRiskLevel(source.getRiskLevel());
        target.setRiskScore(source.getRiskScore());
        target.setMachineConclusion(source.getMachineConclusion());
        target.setMatchedLabels(source.getMatchedLabels());
        target.setSourceType(source.getSourceType());
        target.setSubmittedAt(source.getSubmittedAt());
        target.setWaitingMinutes(waitingMinutes(source.getSubmittedAt(), source.getStatus()));
        target.setReportCount(source.getReportCount());
        target.setAttachmentCount(source.getAttachmentCount());
        target.setStatus(source.getStatus());
        AdminReviewItemVO.Author author = new AdminReviewItemVO.Author();
        author.setId(stringValue(source.getAuthor().getId()));
        author.setNickname(source.getAuthor().getNickname());
        author.setAvatarUrl(source.getAuthor().getAvatarUrl());
        author.setRoleCode(source.getAuthor().getRoleCode());
        author.setAccountStatus(source.getAuthor().getAccountStatus());
        author.setJoinedAt(source.getAuthor().getJoinedAt());
        target.setAuthor(author);
    }

    private long waitingMinutes(LocalDateTime submittedAt, String status) {
        if (submittedAt == null || !"pending".equals(status)) {
            return 0L;
        }
        return Math.max(0L, Duration.between(submittedAt, LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"))).toMinutes());
    }

    private String stringValue(Long value) {
        return value == null ? null : value.toString();
    }
}
