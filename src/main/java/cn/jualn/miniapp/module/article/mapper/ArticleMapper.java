package cn.jualn.miniapp.module.article.mapper;

import cn.jualn.miniapp.module.article.entity.Article;
import cn.jualn.miniapp.module.article.bo.ArticleStatisticsBO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface ArticleMapper extends BaseMapper<Article> {
    @Insert("INSERT INTO article_safety_unpublish(article_id,article_revision,actor,reason,created_at) VALUES(#{id},#{revision},#{actor},#{reason},#{at})")
    int safetyUnpublish(@Param("id") long id, @Param("revision") long revision, @Param("actor") long actor, @Param("reason") String reason, @Param("at") LocalDateTime at);

    @Select("SELECT id FROM article_write_guard WHERE id=1 FOR UPDATE")
    Integer lockGuard();

    @Select("SELECT organization_id FROM article_publisher_organization_mapping WHERE publisher_id=#{id}")
    String mappedOrganization(long id);

    @Select("SELECT COUNT(*) FROM article_organization_audit WHERE article_id=#{id}")
    long organizationAudited(long id);

    @Select("SELECT COUNT(*) FROM article_publisher_migration_evidence WHERE article_id=#{id} AND organization_id=#{org} AND CAST(original_signature AS BINARY)=CAST(#{signature} AS BINARY)")
    long approvedSignature(@Param("id") long id, @Param("org") String org, @Param("signature") String signature);

    @Select("SELECT COUNT(*) FROM article WHERE publisher_id=#{id}")
    long legacyReferences(long id);

    @Insert("INSERT INTO article_organization_audit(id,article_id,actor,operation,old_organization_id,new_organization_id,old_signature,new_signature,authorization_sources,evaluated_at) VALUES(#{id},#{article.id},#{actor},#{operation},#{oldOrg},#{article.publisherOrgId},#{oldName},#{article.publisherNameSnapshot},#{sources},#{at})")
    int organizationAudit(@Param("id") String id, @Param("article") Article article, @Param("oldOrg") String oldOrg, @Param("oldName") String oldName, @Param("actor") long actor, @Param("operation") String operation, @Param("sources") String sources, @Param("at") LocalDateTime at);

    List<Article> publicOptions(@Param("id") Long id, @Param("q") String q, @Param("offset") long offset,
                                @Param("size") int size, @Param("lock") boolean lock);

    long countPublicOptions(@Param("id") Long id, @Param("q") String q);

    List<Article> publicByIds(@Param("ids") List<Long> ids);

    Article lock(@Param("id") long id);

    int replace(Article article);

    List<Article> publicPage(@Param("categoryId") Long categoryId, @Param("includeChildren") boolean includeChildren, @Param("beforeTime") LocalDateTime beforeTime,
                             @Param("beforeId") Long beforeId, @Param("size") int size, @Param("q") String q, @Param("sceneRoots") List<Long> sceneRoots);

    default List<Article> publicPage(Long c, boolean children, LocalDateTime at, Long id, int size) {
        return publicPage(c, children, at, id, size, null, null);
    }

    List<Article> referencePage(@Param("categoryId") Long categoryId, @Param("beforeTime") LocalDateTime beforeTime,
                                @Param("beforeId") Long beforeId, @Param("size") int size, @Param("q") String q,
                                @Param("ascending") boolean ascending);

    List<Article> adminPage(@Param("status") String status, @Param("categoryId") Long categoryId, @Param("includeChildren") boolean includeChildren,
                            @Param("publisherId") Long publisherId, @Param("publisherOrgId") String publisherOrgId, @Param("authorizedOrgs") List<String> authorizedOrgs, @Param("q") String q, @Param("sort") String sort,
                            @Param("offset") long offset, @Param("size") int size, @Param("deleted") boolean deleted);

    long adminCount(@Param("status") String status, @Param("categoryId") Long categoryId, @Param("includeChildren") boolean includeChildren,
                    @Param("publisherId") Long publisherId, @Param("publisherOrgId") String publisherOrgId, @Param("authorizedOrgs") List<String> authorizedOrgs, @Param("q") String q, @Param("deleted") boolean deleted);

    @Select("SELECT COUNT(*) FROM article WHERE id=#{id} AND publish_status='PUBLISHED' AND deleted_at IS NULL")
    boolean visible(long id);

    int writeStatistics(@Param("id") long id, @Param("statistics") ArticleStatisticsBO statistics);

    @Update("UPDATE article SET view_count=view_count+#{delta} WHERE id=#{id}")
    int incrementViewCount(@Param("id") long id, @Param("delta") long delta);

    @Update("UPDATE article SET like_count=#{count} WHERE id=#{id}")
    int setLikeCount(@Param("id") long id, @Param("count") long count);
}
