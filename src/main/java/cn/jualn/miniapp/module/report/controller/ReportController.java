package cn.jualn.miniapp.module.report.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.jualn.miniapp.common.result.PageResult;
import cn.jualn.miniapp.common.result.Result;
import cn.jualn.miniapp.module.report.bo.ReportBO;
import cn.jualn.miniapp.module.report.converter.ReportConverter;
import cn.jualn.miniapp.module.report.dto.request.ReportCreateRequest;
import cn.jualn.miniapp.module.report.dto.request.ReportHandleRequest;
import cn.jualn.miniapp.module.report.dto.request.ReportPageQuery;
import cn.jualn.miniapp.module.report.service.ReportService;
import cn.jualn.miniapp.module.report.vo.ReportVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 举报接口。
 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/reports")
public class ReportController {

    private final ReportService reportService;
    private final ReportConverter reportConverter;

    @SaCheckLogin
    @PostMapping
    public Result<Void> createReport(@Valid @RequestBody ReportCreateRequest request) {
        reportService.createReport(reportConverter.toBO(request));
        return Result.ok(null);
    }

    @SaCheckLogin
    @GetMapping("/me")
    public Result<PageResult<ReportVO>> pageCurrentUserReports(@Valid ReportPageQuery query) {
        PageResult<ReportBO> pageResult = reportService.pageCurrentUserReports(reportConverter.toBO(query));
        return Result.ok(PageResult.of(
                reportConverter.toVOList(pageResult.getList()),
                pageResult.getHasMore(),
                pageResult.getNextCursor()
        ));
    }

    @SaCheckLogin
    @GetMapping("/admin")
    public Result<PageResult<ReportVO>> pageReports(@Valid ReportPageQuery query) {
        PageResult<ReportBO> pageResult = reportService.pageReports(reportConverter.toBO(query));
        return Result.ok(PageResult.of(
                reportConverter.toVOList(pageResult.getList()),
                pageResult.getHasMore(),
                pageResult.getNextCursor()
        ));
    }

    @SaCheckLogin
    @PutMapping("/admin/{reportId}/handle")
    public Result<ReportVO> handleReport(@PathVariable Long reportId,
                                         @Valid @RequestBody ReportHandleRequest request) {
        return Result.ok(reportConverter.toVO(
                reportService.handleReport(reportId, reportConverter.toBO(request))
        ));
    }
}


