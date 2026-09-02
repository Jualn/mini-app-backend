package cn.jualn.miniapp.module.report.service.impl;

import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.pagination.AdminIdCursorCodec;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.module.content.bo.AdminContentActionBO;
import cn.jualn.miniapp.module.content.bo.AdminContentDetailBO;
import cn.jualn.miniapp.module.content.enums.AdminContentAction;
import cn.jualn.miniapp.module.content.enums.AdminContentType;
import cn.jualn.miniapp.module.content.service.AdminContentService;
import cn.jualn.miniapp.module.report.bo.AdminReportCaseDetailBO;
import cn.jualn.miniapp.module.report.bo.AdminReportCaseDecisionBO;
import cn.jualn.miniapp.module.report.bo.AdminReportCaseItemBO;
import cn.jualn.miniapp.module.report.bo.AdminReportCasePageBO;
import cn.jualn.miniapp.module.report.bo.AdminReportCaseQueryBO;
import cn.jualn.miniapp.module.report.mapper.AdminReportCaseRow;
import cn.jualn.miniapp.module.report.mapper.AdminReportCaseSummaryRow;
import cn.jualn.miniapp.module.report.mapper.AdminReportEvidenceRow;
import cn.jualn.miniapp.module.report.mapper.ReportMapper;
import cn.jualn.miniapp.module.report.enums.ReportStatus;
import cn.jualn.miniapp.module.report.service.AdminReportCaseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class AdminReportCaseServiceImpl implements AdminReportCaseService {
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 50;
    private static final String SORT = "latest";

    private final ReportMapper reportMapper;
    private final AdminContentService adminContentService;

    @Override
    public AdminReportCasePageBO pageCases(AdminReportCaseQueryBO query) {
        AdminReportCaseQueryBO actual = query == null ? AdminReportCaseQueryBO.builder().build() : query;
        int pageSize = normalizePageSize(actual.getPageSize());
        Long cursorId = AdminIdCursorCodec.decode(actual.getCursor(), SORT);
        List<AdminReportCaseRow> rows = reportMapper.selectAdminCasePage(
                trimToNull(actual.getKeyword()),
                actual.getStatus(),
                actual.getTargetType(),
                actual.getReason(),
                cursorId,
                pageSize + 1);
        boolean hasMore = rows.size() > pageSize;
        if (hasMore) {
            rows = rows.subList(0, pageSize);
        }
        AdminReportCaseSummaryRow summary = reportMapper.selectAdminCaseSummary();
        return AdminReportCasePageBO.builder()
                .items(rows.stream().map(this::toItem).toList())
                .summary(AdminReportCasePageBO.Summary.builder()
                        .pendingCases(value(summary == null ? null : summary.getPendingCases()))
                        .urgentCases(value(summary == null ? null : summary.getUrgentCases()))
                        .reportsToday(value(summary == null ? null : summary.getReportsToday()))
                        .completedToday(value(summary == null ? null : summary.getCompletedToday()))
                        .build())
                .hasMore(hasMore)
                .nextCursor(!hasMore || rows.isEmpty()
                        ? null
                        : AdminIdCursorCodec.encode(SORT, rows.get(rows.size() - 1).getLatestReportId()))
                .pageSize(pageSize)
                .build();
    }

    @Override
    public AdminReportCaseDetailBO getCaseDetail(String targetType, Long targetId) {
        AdminReportCaseRow row = reportMapper.selectAdminCase(targetType, targetId);
        if (row == null) {
            throw new BusinessException(ResultCode.REPORT_NOT_FOUND, "举报案件不存在");
        }
        AdminContentType contentType = AdminContentType.fromCode(targetType);
        AdminContentDetailBO content = adminContentService.getContentDetail(contentType, targetId);
        AdminReportCaseDetailBO detail = new AdminReportCaseDetailBO();
        BeanUtils.copyProperties(toItem(row), detail);
        detail.setContent(content.getContent());

        AdminReportCaseDetailBO.Author author = new AdminReportCaseDetailBO.Author();
        author.setNickname(content.getAuthorName());
        author.setAvatarText(content.getAuthor().getAvatarText());
        author.setStatusLabel(content.getAuthor().getStatusLabel());
        author.setJoinedAt(content.getAuthor().getJoinedAt());
        author.setPublishedCount(content.getAuthor().getPublishedCount());
        author.setRejectedCount(content.getAuthor().getViolationCount());
        detail.setAuthor(author);

        detail.setEvidence(reportMapper.selectAdminCaseEvidence(targetType, targetId).stream()
                .map(this::toEvidence)
                .toList());
        detail.setContext(content.getContext().stream().map(item -> {
            AdminReportCaseDetailBO.ContextItem target = new AdminReportCaseDetailBO.ContextItem();
            target.setLabel(item.getLabel());
            target.setValue(item.getValue());
            return target;
        }).toList());
        detail.setRelatedAudit(buildAudit(row));
        return detail;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void resolveCase(AdminReportCaseDecisionBO command) {
        if (command == null || command.getOperatorId() == null || command.getTargetId() == null
                || command.getDecision() == null) {
            throw new BusinessException(ResultCode.INVALID_OPERATION, "举报案件结案参数不完整");
        }
        AdminContentType contentType = AdminContentType.fromCode(command.getTargetType());
        if (contentType == null) {
            throw new BusinessException(ResultCode.INVALID_TARGET_TYPE, "仅支持帖子和评论举报案件");
        }
        if (command.getDecision() != ReportStatus.VIOLATION_HANDLED
                && command.getDecision() != ReportStatus.NORMAL_HANDLED) {
            throw new BusinessException(ResultCode.INVALID_OPERATION, "举报案件结论不合法");
        }
        String remark = trimToNull(command.getRemark());
        if (remark == null || remark.length() < 4 || remark.length() > 500) {
            throw new BusinessException(ResultCode.INVALID_OPERATION, "结案备注长度必须在4到500个字符之间");
        }

        Integer targetTypeCode = contentType.getTargetType().getCode();
        List<Long> pendingIds = reportMapper.selectPendingReportIdsForUpdate(
                targetTypeCode, command.getTargetId());
        if (pendingIds.isEmpty()) {
            throw new BusinessException(ResultCode.REPORT_ALREADY_HANDLED, "举报案件已结案");
        }

        if (command.getDecision() == ReportStatus.VIOLATION_HANDLED) {
            AdminContentDetailBO content = adminContentService.getContentDetail(contentType, command.getTargetId());
            if (content.getAvailableActions().contains(AdminContentAction.TAKE_DOWN.getCode())) {
                adminContentService.executeAction(AdminContentActionBO.builder()
                        .operatorId(command.getOperatorId())
                        .type(contentType)
                        .contentId(command.getTargetId())
                        .action(AdminContentAction.TAKE_DOWN)
                        .reason(remark)
                        .build());
            }
        }

        LocalDateTime handledAt = LocalDateTime.now();
        int updated = reportMapper.resolvePendingCase(
                targetTypeCode,
                command.getTargetId(),
                command.getDecision().getCode(),
                command.getOperatorId(),
                remark,
                handledAt);
        if (updated != pendingIds.size()) {
            throw new BusinessException(ResultCode.DATA_CONFLICT, "举报案件状态已变化，请刷新后重试");
        }
        log.info("[AdminReportCaseService.resolveCase][完成] operatorId={}, targetType={}, targetId={}, decision={}, reports={}",
                command.getOperatorId(), command.getTargetType(), command.getTargetId(),
                command.getDecision(), updated);
    }

    private List<AdminReportCaseDetailBO.AuditItem> buildAudit(AdminReportCaseRow row) {
        if (row.getHandledAt() == null || row.getHandlerId() == null || "pending".equals(row.getStatus())) {
            return List.of();
        }
        AdminReportCaseDetailBO.AuditItem item = new AdminReportCaseDetailBO.AuditItem();
        boolean violation = "violation".equals(row.getStatus());
        item.setTitle(violation ? "举报案件结论：内容违规" : "举报案件结论：内容正常");
        item.setDetail("管理员 #" + row.getHandlerId() + " · "
                + (row.getHandleRemark() == null ? "未填写备注" : row.getHandleRemark()));
        item.setCreatedAt(row.getHandledAt());
        item.setTone(violation ? "danger" : "success");
        return List.of(item);
    }

    private AdminReportCaseItemBO toItem(AdminReportCaseRow row) {
        AdminReportCaseItemBO item = new AdminReportCaseItemBO();
        item.setLatestReportId(row.getLatestReportId());
        item.setCaseId(row.getTargetType() + ":" + row.getTargetId());
        item.setTargetId(row.getTargetId().toString());
        item.setTargetType(row.getTargetType());
        item.setTitle(row.getTitle());
        item.setContentPreview(row.getContentPreview());
        item.setAuthorName(row.getAuthorName());
        item.setAuthorId(row.getAuthorId() == null ? "" : row.getAuthorId().toString());
        item.setReportCount(value(row.getReportCount()));
        item.setReasons(reasonGroups(row));
        item.setRiskLevel(riskLevel(row));
        item.setStatus(row.getStatus());
        item.setFirstReportedAt(row.getFirstReportedAt());
        item.setLastReportedAt(row.getLastReportedAt());
        item.setWaitingMinutes("pending".equals(row.getStatus())
                ? Math.max(0, Duration.between(row.getFirstReportedAt(), LocalDateTime.now()).toMinutes())
                : 0L);
        item.setPreviousAuditLabel(previousAuditLabel(row));
        return item;
    }

    private List<AdminReportCaseItemBO.ReasonGroup> reasonGroups(AdminReportCaseRow row) {
        List<AdminReportCaseItemBO.ReasonGroup> groups = new ArrayList<>();
        addReason(groups, "illegal", "违规违法", row.getIllegalCount());
        addReason(groups, "porn", "色情低俗", row.getPornCount());
        addReason(groups, "advertising", "广告骚扰", row.getAdvertisingCount());
        addReason(groups, "false-info", "虚假信息", row.getFalseInfoCount());
        addReason(groups, "other", "其他", row.getOtherCount());
        return groups;
    }

    private void addReason(List<AdminReportCaseItemBO.ReasonGroup> groups, String code, String label, Long count) {
        if (value(count) == 0) {
            return;
        }
        AdminReportCaseItemBO.ReasonGroup group = new AdminReportCaseItemBO.ReasonGroup();
        group.setCode(code);
        group.setLabel(label);
        group.setCount(count);
        groups.add(group);
    }

    private String riskLevel(AdminReportCaseRow row) {
        long count = value(row.getReportCount());
        if (count >= 4) return "urgent";
        if (count >= 3 || value(row.getIllegalCount()) > 0 || value(row.getPornCount()) > 0) return "high";
        return count >= 2 ? "medium" : "low";
    }

    private String previousAuditLabel(AdminReportCaseRow row) {
        if ("pending".equals(row.getStatus())) return "尚未形成举报结论";
        String conclusion = "violation".equals(row.getStatus()) ? "最近结论：内容违规" : "最近结论：内容正常";
        return row.getHandleRemark() == null || row.getHandleRemark().isBlank()
                ? conclusion
                : conclusion + " · " + row.getHandleRemark();
    }

    private AdminReportCaseDetailBO.Evidence toEvidence(AdminReportEvidenceRow row) {
        AdminReportCaseDetailBO.Evidence evidence = new AdminReportCaseDetailBO.Evidence();
        evidence.setReportId(row.getReportId().toString());
        evidence.setReporterName(row.getReporterName());
        evidence.setReporterId(row.getReporterId().toString());
        evidence.setReason(row.getReason());
        evidence.setRemark(row.getRemark());
        evidence.setCreatedAt(row.getCreatedAt());
        return evidence;
    }

    private int normalizePageSize(Integer pageSize) {
        if (pageSize == null || pageSize < 1) return DEFAULT_PAGE_SIZE;
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }

    private long value(Long number) {
        return number == null ? 0L : number;
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
