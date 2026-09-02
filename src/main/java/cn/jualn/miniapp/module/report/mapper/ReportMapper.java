package cn.jualn.miniapp.module.report.mapper;

import cn.jualn.miniapp.module.report.entity.Report;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.time.LocalDateTime;

/**
 * 举报记录 Mapper。
 */
public interface ReportMapper extends BaseMapper<Report> {
    List<AdminReportCaseRow> selectAdminCasePage(
            @Param("keyword") String keyword,
            @Param("status") String status,
            @Param("targetType") String targetType,
            @Param("reason") String reason,
            @Param("cursorId") Long cursorId,
            @Param("limit") int limit);

    AdminReportCaseRow selectAdminCase(
            @Param("targetType") String targetType,
            @Param("targetId") Long targetId);

    AdminReportCaseSummaryRow selectAdminCaseSummary();

    List<AdminReportEvidenceRow> selectAdminCaseEvidence(
            @Param("targetType") String targetType,
            @Param("targetId") Long targetId);

    List<Long> selectPendingReportIdsForUpdate(
            @Param("targetType") Integer targetType,
            @Param("targetId") Long targetId);

    int resolvePendingCase(
            @Param("targetType") Integer targetType,
            @Param("targetId") Long targetId,
            @Param("status") Integer status,
            @Param("handlerId") Long handlerId,
            @Param("remark") String remark,
            @Param("handledAt") LocalDateTime handledAt);
}

