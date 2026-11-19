package cn.jualn.miniapp.module.article.service.impl;

import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.module.article.entity.Article;
import cn.jualn.miniapp.module.article.mapper.ArticleMapper;
import cn.jualn.miniapp.module.article.mapper.ArticlePublisherMapper;
import cn.jualn.miniapp.module.article.service.*;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class ArticleReferenceReadServiceImpl implements ArticleReferenceReadService {
    private final ArticleMapper articles;
    private final ArticlePublisherMapper publishers;

    public ArticleReferenceReadServiceImpl(ArticleMapper articles, ArticlePublisherMapper publishers) {
        this.articles = articles;
        this.publishers = publishers;
    }

    @Override
    public Options options(String q, String cursor, int size, String binding) {
        if (size < 1 || size > 50 || q != null && (q.isBlank() || ArticleBodyCodec.length(q) > 120))
            ArticleBodyCodec.invalidQuery("#", "Invalid Article options query");
        String context = "task-article-options/" + binding + "/" + ArticleCursor.fingerprint(q);
        var position = ArticleCursor.decode(cursor, context);
        java.time.LocalDateTime at = null;
        Long id = null;
        if (position != null) try {
            var parts = position.split("\\|", -1);
            if (parts.length != 2) throw new IllegalArgumentException();
            at = java.time.LocalDateTime.parse(parts[0]);
            id = Long.valueOf(parts[1]);
            if (id <= 0) throw new IllegalArgumentException();
        } catch (IllegalArgumentException e) {
            throw ArticleCursor.invalid();
        }
        var rows = articles.publicPage(null, false, at, id, size + 1, q, null);
        boolean more = rows.size() > size;
        var page = more ? rows.subList(0, size) : rows;
        var oldIds = page.stream().map(Article::getPublisherId).filter(java.util.Objects::nonNull).distinct().toList();
        var old = new java.util.HashMap<Long, String>();
        if (!oldIds.isEmpty()) publishers.selectBatchIds(oldIds).forEach(p -> old.put(p.getId(), p.getDisplayName()));
        var items = page.stream().map(a -> {
            boolean org = Boolean.TRUE.equals(a.getOrganizationRepresentation());
            if (!org && a.getPublisherId() == null)
                throw ArticleOrganizationPolicy.unavailable();
            return new Option(a.getId(), a.getTitle(), a.getPublishedAt(), org ? a.getPublisherNameSnapshot() : null,
                    org ? null : new Publisher(a.getPublisherId(), ArticleWriter.require(old.get(a.getPublisherId()))));
        }).toList();
        var last = page.isEmpty() ? null : page.get(page.size() - 1);
        return new Options(items, more ? ArticleCursor.encode(last.getPublishedAt() + "|" + last.getId(), context) : null);
    }

    @Override
    public void requirePublicReference(long id) {
        try {
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                lockPublicReference(id);
            } else {
                var row = articles.selectById(id);
                if (row == null || row.getDeletedAt() != null || !"PUBLISHED".equals(row.getPublishStatus()))
                    throw ArticleOrganizationPolicy.unavailable();
                requireReadableIdentity(row);
            }
        } catch (DataAccessException e) {
            var error = new ContractProblemException(HttpStatus.SERVICE_UNAVAILABLE, "/problems/service-unavailable", "Article availability is unknown");
            error.initCause(e);
            throw error;
        }
    }

    private void lockPublicReference(long id) {
        ArticleWriter.require(articles.lockGuard());
        var row = articles.lock(id);
        if (row == null || row.getDeletedAt() != null || !"PUBLISHED".equals(row.getPublishStatus()))
            throw ArticleOrganizationPolicy.unavailable();
        requireReadableIdentity(row);
    }

    private void requireReadableIdentity(Article row) {
        if (row.getPublisherId() != null)
            return; // Existing verified legacy Publisher display remains a public read branch.
        if (row.getPublisherOrgId() == null || row.getPublisherNameSnapshot() == null || articles.organizationAudited(row.getId()) == 0)
            throw ArticleOrganizationPolicy.unavailable();
    }

    @Override
    public Set<Long> publiclyVisibleIds(Set<Long> ids) {
        if (ids.isEmpty()) {
            return Set.of();
        }
        return articles.publicByIds(List.copyOf(ids)).stream().peek(a -> {
            if (!Boolean.TRUE.equals(a.getOrganizationRepresentation()) && a.getPublisherId() == null)
                throw ArticleOrganizationPolicy.unavailable();
        }).map(Article::getId).collect(Collectors.toUnmodifiableSet());
    }
}
