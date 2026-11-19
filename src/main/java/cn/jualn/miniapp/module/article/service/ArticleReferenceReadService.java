package cn.jualn.miniapp.module.article.service;

import java.util.Set;

/**
 * Public availability and minimal material metadata; management and reference admission remain separate.
 */
public interface ArticleReferenceReadService {
    record Publisher(Long id, String displayName) {
    }

    record Option(Long articleId, String title, java.time.LocalDateTime publishedAt, String publisherNameSnapshot,
                  Publisher publisher) {
    }

    record Options(java.util.List<Option> items, String nextCursor) {
    }

    Options options(String q, String cursor, int size, String authorizationBinding);

    void requirePublicReference(long articleId);

    Set<Long> publiclyVisibleIds(Set<Long> ids);
}
