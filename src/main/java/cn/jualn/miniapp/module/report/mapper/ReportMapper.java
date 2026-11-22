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
    @org.apache.ibatis.annotations.Select("SELECT * FROM report WHERE id=#{id} FOR UPDATE")
    Report lockReport(long id);
    List<AdminReportCaseRow> selectAdminCasePage(
            @Param("keyword") String keyword,
            @Param("status") String status,
            @Param("targetType") String targetType,
            @Param("reason") String reason,
            @Param("cursorId") Long cursorId,
            @Param("limit") int limit);
    List<AdminReportCaseRow> selectGrowthCasePage(
            @Param("keyword") String keyword,
            @Param("status") String status,
            @Param("targetType") String targetType,
            @Param("reason") String reason,
            @Param("cursorId") Long cursorId,
            @Param("limit") int limit,@Param("growthType") String growthType,@Param("growthOrganizationId") String growthOrganizationId,@Param("growthOrganizations") List<String> growthOrganizations);

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

