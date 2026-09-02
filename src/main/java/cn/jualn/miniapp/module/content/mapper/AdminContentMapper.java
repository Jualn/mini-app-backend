package cn.jualn.miniapp.module.content.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AdminContentMapper {

    List<AdminContentListRow> selectPage(
            @Param("keyword") String keyword,
            @Param("keywordId") Long keywordId,
            @Param("type") String type,
            @Param("status") String status,
            @Param("feature") String feature,
            @Param("sort") String sort,
            @Param("cursorUpdatedAt") LocalDateTime cursorUpdatedAt,
            @Param("cursorMetric") Long cursorMetric,
            @Param("cursorType") String cursorType,
            @Param("cursorId") Long cursorId,
            @Param("limit") Integer limit);

    AdminContentSummaryRow selectSummary();

    AdminContentDetailRow selectPostDetail(@Param("contentId") Long contentId);

    AdminContentDetailRow selectCommentDetail(@Param("contentId") Long contentId);
}
