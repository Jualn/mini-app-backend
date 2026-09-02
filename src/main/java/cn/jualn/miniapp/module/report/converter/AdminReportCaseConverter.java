package cn.jualn.miniapp.module.report.converter;

import cn.jualn.miniapp.module.report.bo.AdminReportCaseDetailBO;
import cn.jualn.miniapp.module.report.bo.AdminReportCaseDecisionBO;
import cn.jualn.miniapp.module.report.bo.AdminReportCaseItemBO;
import cn.jualn.miniapp.module.report.bo.AdminReportCasePageBO;
import cn.jualn.miniapp.module.report.bo.AdminReportCaseQueryBO;
import cn.jualn.miniapp.module.report.dto.admin.AdminReportCaseDecisionRequest;
import cn.jualn.miniapp.module.report.dto.admin.AdminReportCasePageQuery;
import cn.jualn.miniapp.module.report.enums.ReportStatus;
import cn.jualn.miniapp.module.report.vo.admin.AdminReportCaseDetailVO;
import cn.jualn.miniapp.module.report.vo.admin.AdminReportCaseItemVO;
import cn.jualn.miniapp.module.report.vo.admin.AdminReportCasePageVO;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Component;

@Component
public class AdminReportCaseConverter {
    public AdminReportCaseDecisionBO toDecisionBO(
            Long operatorId,
            String targetType,
            Long targetId,
            AdminReportCaseDecisionRequest request) {
        return AdminReportCaseDecisionBO.builder()
                .operatorId(operatorId)
                .targetType(targetType)
                .targetId(targetId)
                .decision("violation".equals(request.getDecision())
                        ? ReportStatus.VIOLATION_HANDLED
                        : ReportStatus.NORMAL_HANDLED)
                .remark(request.getRemark().trim())
                .build();
    }

    public AdminReportCaseQueryBO toQueryBO(AdminReportCasePageQuery query) {
        return AdminReportCaseQueryBO.builder()
                .keyword(query.getKeyword())
                .status(query.getStatus())
                .targetType(query.getTargetType())
                .reason(query.getReason())
                .cursor(query.getCursor())
                .pageSize(query.getPageSize())
                .build();
    }

    public AdminReportCasePageVO toPageVO(AdminReportCasePageBO page) {
        return AdminReportCasePageVO.builder()
                .items(page.getItems().stream().map(this::toItemVO).toList())
                .summary(AdminReportCasePageVO.Summary.builder()
                        .pendingCases(page.getSummary().getPendingCases())
                        .urgentCases(page.getSummary().getUrgentCases())
                        .reportsToday(page.getSummary().getReportsToday())
                        .completedToday(page.getSummary().getCompletedToday())
                        .build())
                .hasMore(page.getHasMore())
                .nextCursor(page.getNextCursor())
                .pageSize(page.getPageSize())
                .build();
    }

    public AdminReportCaseDetailVO toDetailVO(AdminReportCaseDetailBO detail) {
        AdminReportCaseDetailVO vo = new AdminReportCaseDetailVO();
        copyItem(detail, vo);
        vo.setContent(detail.getContent());
        AdminReportCaseDetailVO.Author author = new AdminReportCaseDetailVO.Author();
        BeanUtils.copyProperties(detail.getAuthor(), author);
        vo.setAuthor(author);
        vo.setEvidence(detail.getEvidence().stream().map(item -> {
            AdminReportCaseDetailVO.Evidence target = new AdminReportCaseDetailVO.Evidence();
            BeanUtils.copyProperties(item, target);
            return target;
        }).toList());
        vo.setContext(detail.getContext().stream().map(item -> {
            AdminReportCaseDetailVO.ContextItem target = new AdminReportCaseDetailVO.ContextItem();
            BeanUtils.copyProperties(item, target);
            return target;
        }).toList());
        vo.setRelatedAudit(detail.getRelatedAudit().stream().map(item -> {
            AdminReportCaseDetailVO.AuditItem target = new AdminReportCaseDetailVO.AuditItem();
            BeanUtils.copyProperties(item, target);
            return target;
        }).toList());
        return vo;
    }

    private AdminReportCaseItemVO toItemVO(AdminReportCaseItemBO item) {
        AdminReportCaseItemVO vo = new AdminReportCaseItemVO();
        copyItem(item, vo);
        return vo;
    }

    private void copyItem(AdminReportCaseItemBO item, AdminReportCaseItemVO vo) {
        BeanUtils.copyProperties(item, vo, "reasons", "latestReportId");
        vo.setReasons(item.getReasons().stream().map(reason -> {
            AdminReportCaseItemVO.ReasonGroup target = new AdminReportCaseItemVO.ReasonGroup();
            BeanUtils.copyProperties(reason, target);
            return target;
        }).toList());
    }
}
