package cn.jualn.miniapp.module.article.service;

import java.time.LocalDateTime;

import cn.jualn.miniapp.module.article.bo.*;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface ArticleService {
    record PublicCard(long id, String title, String summary, LocalDateTime publishedAt, Long coverAttachmentId) {
    }

    record PublicOptions(List<PublicCard> items, long totalItems) {
    }

    List<PublicCard> recentPublicCards(String scene, int limit);

    PublicCard publicCard(long id, boolean lock);

    PublicOptions publicOptions(Long id, String titleQuery, long offset, int limit);

    ArticleBO getPublic(long id);

    java.util.Map<Long, ArticleBO> publicSummaries(java.util.List<Long> ids);

    @Transactional(readOnly = true)
    default ArticlePageBO listPublic(Long categoryId, String cursor, int pageSize, String sort) {
        return listPublic(categoryId, null, cursor, pageSize, sort);
    }

    @Transactional(readOnly = true)
    default ArticlePageBO listPublic(Long categoryId, Boolean includeChildren, String cursor, int pageSize, String sort) {
        return listPublic(categoryId, includeChildren, cursor, pageSize, sort, null, null);
    }

    ArticlePageBO listPublic(Long categoryId, Boolean includeChildren, String cursor, int pageSize, String sort, String q, String sceneCode);

    ArticleBO getAdmin(long id);

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    default ArticleAdminPageBO listAdmin(int page, int pageSize, String status, Long categoryId, Long publisherId, String q, String sort) {
        return listAdmin(page, pageSize, status, categoryId, null, publisherId, null, q, sort);
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    default ArticleAdminPageBO listAdmin(int page, int pageSize, String status, Long categoryId, Boolean includeChildren, Long publisherId, String q, String sort) {
        return listAdmin(page, pageSize, status, categoryId, includeChildren, publisherId, null, q, sort);
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    default ArticleAdminPageBO listAdmin(int page, int pageSize, String status, Long categoryId, Boolean includeChildren, Long publisherId, String publisherOrgId, String q, String sort) {
        return listAdmin(page, pageSize, status, categoryId, includeChildren, publisherId, publisherOrgId, q, sort, false);
    }

    ArticleAdminPageBO listAdmin(int page, int pageSize, String status, Long categoryId, Boolean includeChildren, Long publisherId, String publisherOrgId, String q, String sort, boolean deleted);

    ArticleBO create(ArticleSaveBO command);

    ArticleBO replace(long id, ArticleSaveBO command, String ifMatch);

    ArticleBO publish(long id, String ifMatch);

    ArticleBO unpublish(long id, String ifMatch);

    ArticleBO unpublish(long id, String ifMatch, String mode, String reason);

    void delete(long id, String ifMatch);

    ArticleBO restore(long id, String ifMatch);

    record Usage(boolean hasReferences, boolean canDelete, boolean hasActiveReferences, boolean canUnpublish) {
        public Usage(boolean hasReferences, boolean canDelete) {
            this(hasReferences, canDelete, false, false);
        }
    }

    Usage learningTaskUsage(long id);

    boolean isPubliclyVisible(long id);

    void assertPublic(long id);

    void lockInteraction(long id, boolean newComment);

    void lockExisting(long id);

    String publicTitle(long id);

    ArticleStatisticsBO statistics(long id);

    void rebuildStatistics(long id);

    List<ArticleCategoryBO> categories(boolean controlled);

    ArticleCategoryBO category(long id);

    ArticleCategoryBO createCategory(ArticleCategorySaveBO command);

    ArticleCategoryBO replaceCategory(long id, ArticleCategorySaveBO command, String ifMatch);

    List<ArticlePublisherBO> publishers();

    ArticlePublisherBO publisher(long id);

    ArticlePublisherBO replacePublisher(long id, String name, String ifMatch);
}
