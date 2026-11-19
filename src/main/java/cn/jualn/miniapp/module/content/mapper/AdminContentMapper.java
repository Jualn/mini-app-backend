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
            @Param("postType") String postType,@Param("categoryId") Long categoryId,@Param("topicId") Long topicId,
            @Param("limit") Integer limit,@Param("namespaces") java.util.List<String> namespaces);
    List<AdminContentListRow> selectGrowthPage(
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
            @Param("postType") String postType,@Param("categoryId") Long categoryId,@Param("topicId") Long topicId,
            @Param("growthType") String growthType,@Param("growthOrganizationId") String growthOrganizationId,@Param("growthOrganizations") List<String> growthOrganizations,@Param("limit") Integer limit,@Param("namespaces") java.util.List<String> namespaces);

    @org.apache.ibatis.annotations.Select("SELECT p.id AS question,NULL AS answer_root,NULL AS reply_to FROM post p WHERE p.id=#{id} AND p.post_type='MENTOR_QA'")
    cn.jualn.miniapp.module.content.bo.AdminPostProjection.Relation postRelation(long id);
    @org.apache.ibatis.annotations.Select("SELECT p.id AS question,COALESCE(c.parent_id,c.id) AS answer_root,c.reply_to_comment_id AS reply_to,c.question_version FROM comment c JOIN post p ON c.target_type=1 AND c.target_id=p.id AND p.post_type='MENTOR_QA' WHERE c.id=#{id}")
    cn.jualn.miniapp.module.content.bo.AdminPostProjection.Relation commentRelation(long id);
    @org.apache.ibatis.annotations.Select("SELECT LEFT(content,512) FROM post WHERE id=#{id} AND post_type='MENTOR_QA' AND deleted_at IS NULL AND status != 4")
    String questionPreview(long id);
    @org.apache.ibatis.annotations.Select("SELECT LEFT(content,512) FROM comment WHERE id=#{id} AND target_type=1 AND target_id=#{question} AND deleted_at IS NULL AND status NOT IN (3,4) AND ((#{root}=id AND parent_id IS NULL AND mentor_evidence IS NOT NULL AND CHAR_LENGTH(TRIM(mentor_evidence))>0) OR parent_id=#{root})")
    String answerPreview(@Param("id") long id,@Param("question") long question,@Param("root") long root);
    AdminContentSummaryRow selectGrowthSummary(@Param("type") String type,@Param("growthType") String growthType,@Param("growthOrganizationId") String growthOrganizationId,@Param("growthOrganizations") List<String> growthOrganizations);
    AdminContentSummaryRow selectSummary();
    AdminContentSummaryRow selectScopedSummary(@Param("type") String type,@Param("namespaces") java.util.List<String> namespaces);

    AdminContentDetailRow selectPostDetail(@Param("contentId") Long contentId);

    AdminContentDetailRow selectCommentDetail(@Param("contentId") Long contentId);
}
