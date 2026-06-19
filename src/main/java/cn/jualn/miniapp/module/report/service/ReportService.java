package cn.jualn.miniapp.module.report.service;

import cn.jualn.miniapp.common.result.PageResult;
import cn.jualn.miniapp.module.report.bo.ReportBO;
import cn.jualn.miniapp.module.report.bo.ReportCreateBO;
import cn.jualn.miniapp.module.report.bo.ReportHandleBO;
import cn.jualn.miniapp.module.report.bo.ReportPageBO;

/**
 * 举报服务。
 */
public interface ReportService {

    /**
     * 创建举报。
     */
    ReportBO createReport(ReportCreateBO request);

    /**
     * 分页查询当前登录用户的举报记录。
     */
    PageResult<ReportBO> pageCurrentUserReports(ReportPageBO query);

    /**
     * 管理员分页查询举报记录。
     */
    PageResult<ReportBO> pageReports(ReportPageBO query);

    /**
     * 处理举报。
     */
    ReportBO handleReport(Long reportId, ReportHandleBO request);
}

