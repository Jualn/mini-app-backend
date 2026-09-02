package cn.jualn.miniapp.module.audit.mapper;

import cn.jualn.miniapp.module.audit.entity.ContentAuditLog;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface ContentAuditLogMapper extends BaseMapper<ContentAuditLog> {
    List<AdminReviewRow> selectAdminReviewPage(
            @Param("keyword") String keyword,
            @Param("keywordId") Long keywordId,
            @Param("targetType") Integer targetType,
            @Param("tab") String tab,
            @Param("sort") String sort,
            @Param("cursorSubmittedAt") LocalDateTime cursorSubmittedAt,
            @Param("cursorTaskId") Long cursorTaskId,
            @Param("limit") Integer limit);

    long countAdminReviews(
            @Param("keyword") String keyword,
            @Param("keywordId") Long keywordId,
            @Param("targetType") Integer targetType,
            @Param("tab") String tab);

    AdminReviewSummaryRow selectAdminReviewSummary();

    AdminReviewRow selectAdminReviewDetail(@Param("taskId") Long taskId);

    List<ContentAuditLog> selectAdminReviewHistory(
            @Param("targetType") Integer targetType,
            @Param("targetId") Long targetId);

    ContentAuditLog selectRiskTaskForUpdate(@Param("taskId") Long taskId);

    Long selectRepresentativeRiskTaskId(
            @Param("targetType") Integer targetType,
            @Param("targetId") Long targetId);

    long countPendingAutomaticAudits(
            @Param("targetType") Integer targetType,
            @Param("targetId") Long targetId);

    long countManualDecisions(
            @Param("targetType") Integer targetType,
            @Param("targetId") Long targetId);

    ContentAuditLog selectManualDecisionByIdempotencyKey(
            @Param("idempotencyKey") String idempotencyKey);
}
