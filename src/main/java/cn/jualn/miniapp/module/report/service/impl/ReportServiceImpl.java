package cn.jualn.miniapp.module.report.service.impl;

import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.enums.UserRole;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.PageResult;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.infrastructure.validator.TargetValidator;
import cn.jualn.miniapp.module.report.bo.ReportBO;
import cn.jualn.miniapp.module.report.bo.ReportCreateBO;
import cn.jualn.miniapp.module.report.bo.ReportHandleBO;
import cn.jualn.miniapp.module.report.bo.ReportPageBO;
import cn.jualn.miniapp.module.report.converter.ReportConverter;
import cn.jualn.miniapp.module.report.entity.Report;
import cn.jualn.miniapp.module.report.enums.ReportStatus;
import cn.jualn.miniapp.module.report.mapper.ReportMapper;
import cn.jualn.miniapp.module.report.service.ReportService;
import cn.jualn.miniapp.module.user.bo.UserAuthBO;
import cn.jualn.miniapp.module.user.service.UserService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * 举报服务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportServiceImpl implements ReportService {

    private static final Set<TargetType> ALLOWED_TARGET_TYPES =
            Set.of(TargetType.POST, TargetType.COMMENT, TargetType.USER);
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 50;

    private final ReportMapper reportMapper;
    private final ReportConverter reportConverter;
    private final TargetValidator targetValidator;
    private final UserService userService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReportBO createReport(ReportCreateBO request) {
        Long userId = requireUserId();
        assertTargetType(request.getTargetType());
        targetValidator.assertExists(request.getTargetType(), request.getTargetId());

        Report report = reportConverter.toEntity(request);
        report.setReporterId(userId);

        try {
            reportMapper.insert(report);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(ResultCode.REPORT_DUPLICATE);
        }

        log.info("[ReportService.createReport][完成] userId={}, reportId={}", userId, report.getId());
        return reportConverter.toBO(report);
    }

    @Override
    public PageResult<ReportBO> pageCurrentUserReports(ReportPageBO query) {
        ReportPageBO actualQuery = query == null ? new ReportPageBO() : query;
        actualQuery.setReporterId(requireUserId());
        return pageReportsInternal(actualQuery);
    }

    @Override
    public PageResult<ReportBO> pageReports(ReportPageBO query) {
        requireOperatorOrAdmin();
        return pageReportsInternal(query);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReportBO handleReport(Long reportId, ReportHandleBO request) {
        requireOperatorOrAdmin();

        if (reportId == null) {
            throw new BusinessException(ResultCode.INVALID_OPERATION, "reportId 不能为空");
        }

        Report report = reportMapper.selectById(
                new LambdaQueryWrapper<Report>()
                        .select(Report::getStatus)
                        .eq(Report::getId, reportId)
        );

        if (report == null) {
            throw new BusinessException(ResultCode.REPORT_NOT_FOUND);
        }
        if (report.getStatus() != ReportStatus.PENDING.getCode()) {
            throw new BusinessException(ResultCode.REPORT_ALREADY_HANDLED);
        }

        report.setId(reportId);
        report.setStatus(request.getStatus().getCode());
        report.setHandlerId(requireUserId());
        report.setHandleRemark(request.getHandleRemark());
        report.setHandledAt(LocalDateTime.now());
        reportMapper.updateById(report);

        log.info("[ReportService.handleReport][完成] reportId={}, status={}", reportId, request.getStatus());
        return reportConverter.toBO(report);
    }

    private PageResult<ReportBO> pageReportsInternal(ReportPageBO query) {
        ReportPageBO actualQuery = query == null ? new ReportPageBO() : query;
        int pageSize = normalizePageSize(actualQuery.getPageSize());

        Integer targetTypeCode = actualQuery.getTargetType() == null
                ? null
                : actualQuery.getTargetType().getCode();
        Integer statusCode = actualQuery.getStatus() == null
                ? null
                : actualQuery.getStatus().getCode();
        LambdaQueryWrapper<Report> wrapper = new LambdaQueryWrapper<Report>()
                .select(Report::getId, Report::getReporterId, Report::getTargetType, Report::getTargetId,
                        Report::getReason, Report::getStatus, Report::getCreatedAt)
                .eq(actualQuery.getReporterId() != null, Report::getReporterId, actualQuery.getReporterId())
                .eq(statusCode != null, Report::getStatus, statusCode)
                .eq(targetTypeCode != null, Report::getTargetType, targetTypeCode)
                .eq(actualQuery.getTargetId() != null, Report::getTargetId, actualQuery.getTargetId())
                .lt(actualQuery.getLastId() != null, Report::getId, actualQuery.getLastId())
                .orderByDesc(Report::getId)
                .last("LIMIT " + (pageSize + 1));

        List<Report> reports = reportMapper.selectList(wrapper);
        boolean hasMore = reports.size() > pageSize;
        if (hasMore) {
            reports = reports.subList(0, pageSize);
        }

        List<ReportBO> list = reportConverter.toBOList(reports);
        return PageResult.of(list, hasMore, list.isEmpty() ? null : list.get(list.size() - 1).getId());
    }

    private Long requireUserId() {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        return userId;
    }

    private void requireOperatorOrAdmin() {
        UserAuthBO currentProfile = userService.getUserAuthInfo(requireUserId());
        UserRole userRole = currentProfile.getRole();
        if (userRole != UserRole.OPR && userRole != UserRole.ADMIN) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
    }

    private int normalizePageSize(Integer pageSize) {
        int actualPageSize = pageSize == null ? DEFAULT_PAGE_SIZE : pageSize;
        if (actualPageSize < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(actualPageSize, MAX_PAGE_SIZE);
    }

    private void assertTargetType(TargetType targetType) {
        if (!ALLOWED_TARGET_TYPES.contains(targetType)) {
            throw new BusinessException(ResultCode.REPORT_TARGET_UNSUPPORTED);
        }
    }
}




