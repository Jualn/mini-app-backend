package cn.jualn.miniapp.module.article.service;

import java.util.Set;

/**
 * Public availability and minimal material metadata; management and reference admission remain separate.
 */
public interface ArticleReferenceReadService {
    record Publisher(Long id, String displayName) {
    }

    record Option(Long articleId, String title, java.time.LocalDateTime publishedAt, String publisherNameSnapshot,
                  Publisher publisher, Long categoryId) {
        public Option(Long articleId, String title, java.time.LocalDateTime publishedAt, String publisherNameSnapshot, Publisher publisher) {
            this(articleId, title, publishedAt, publisherNameSnapshot, publisher, null);
        }
    }

    record Category(Long id, String displayName, Long parentId) {}

    record Options(java.util.List<Option> items, String nextCursor, java.util.List<Category> categoryOptions) {
        public Options(java.util.List<Option> items, String nextCursor) { this(items, nextCursor, java.util.List.of()); }
    }

    Options options(String q, String cursor, int size, String authorizationBinding);

    Options options(String q, String cursor, int size, String authorizationBinding, Long categoryId, String sort);

    void requirePublicReference(long articleId);

    Set<Long> publiclyVisibleIds(Set<Long> ids);
}
