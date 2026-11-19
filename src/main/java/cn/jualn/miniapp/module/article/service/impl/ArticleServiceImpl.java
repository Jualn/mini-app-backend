package cn.jualn.miniapp.module.article.service.impl;

import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.common.web.StrongEtag;
import cn.jualn.miniapp.module.article.bo.*;
import cn.jualn.miniapp.module.article.entity.*;
import cn.jualn.miniapp.module.article.mapper.*;
import cn.jualn.miniapp.module.article.service.*;
import cn.jualn.miniapp.module.interact.service.InteractionStatisticsService;
import cn.jualn.miniapp.module.learningtask.service.LearningTaskArticleUsage;
import cn.jualn.miniapp.module.media.service.ArticleMediaService;
import cn.jualn.miniapp.module.media.service.MediaQueries;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.*;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@RequiredArgsConstructor
public class ArticleServiceImpl implements ArticleService {
    private final ArticleMapper articles;
    private final LearningTaskArticleUsage taskUsage;
    private final ArticleCategoryMapper categories;
    private final ArticlePublisherMapper publishers;
    private final ArticleWriter writer;
    private final ArticleOrganizationPolicy organizations;
    private final ArticleAuthorization authorization;
    private final ArticleBodyCodec codec;
    private final ArticleSafetyChecks safety;
    private final MediaQueries media;
    private final ArticleMediaService articleMedia;
    private final ObjectMapper json;
    private final InteractionStatisticsService interactionStatistics;

    @Override
    @Transactional(readOnly = true)
    public ArticleBO getPublic(long id) {
        Article value = ArticleWriter.require(articles.selectById(id));
        assertPublic(id);
        return result(value, true, true);
    }

    @Override
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public ArticleBO getAdmin(long id) {
        long actor = organizations.begin("article:read");
        var row = ArticleWriter.require(articles.selectById(id));
        // Preserve the existing exact ARTICLE/read controlled legacy policy, never as write scope.
        if (effectiveOrganization(row) != null && !organizations.readable(actor).contains(row.getPublisherOrgId()))
            ArticleWriter.require(null);
        if (effectiveOrganization(row) == null && row.getPublisherId() == null)
            throw ArticleOrganizationPolicy.unavailable();
        return result(row, false, true);
    }

    @Override
    public boolean isPubliclyVisible(long id) {
        if (!articles.visible(id)) return false;
        effectiveOrganization(ArticleWriter.require(articles.selectById(id)));
        return true;
    }

    @Override
    public void assertPublic(long id) {
        if (!isPubliclyVisible(id)) ArticleWriter.require(null);
    }

    @Override
    public String publicTitle(long id) {
        assertPublic(id);
        return articles.selectOne(new LambdaQueryWrapper<Article>().select(Article::getTitle).eq(Article::getId, id)).getTitle();
    }

    @Override
    public void lockInteraction(long id, boolean newComment) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Article interaction requires a transaction");
        Article value = ArticleWriter.require(articles.lock(id));
        if (value.getDeletedAt() != null || !"PUBLISHED".equals(value.getPublishStatus())) ArticleWriter.require(null);
        effectiveOrganization(value);
        if (newComment && !Boolean.TRUE.equals(value.getCommentsEnabled()))
            throw ContractProblemException.conflict("comments-disabled", "Comments are disabled");
    }

    @Override
    public void lockExisting(long id) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Article mutation requires a transaction");
        ArticleWriter.require(articles.lock(id));
    }

    @Override
    public ArticleStatisticsBO statistics(long id) {
        assertPublic(id);
        return factStatistics(id);
    }

    @Override
    public void rebuildStatistics(long id) {
        articles.writeStatistics(id, factStatistics(id));
    }

    private ArticleStatisticsBO factStatistics(long id) {
        var value = ArticleWriter.require(interactionStatistics.counts(cn.jualn.miniapp.common.enums.TargetType.ARTICLE, List.of(id)).get(id));
        return new ArticleStatisticsBO(value.viewCount(), value.likeCount(), value.commentCount(), value.shareCount());
    }

    @Override
    @Transactional(readOnly = true)
    public ArticlePageBO listPublic(Long categoryId, Boolean includeChildren, String cursor, int size, String sort, String q, String sceneCode) {
        size(size);
        if (!"-publishedAt".equals(sort)) ArticleBodyCodec.invalidQuery("#/sort", "Unsupported sort");
        validateFilter(categoryId, includeChildren);
        if (q != null && (q.isBlank() || ArticleBodyCodec.length(q) > 120))
            ArticleBodyCodec.invalidQuery("#/q", "Invalid search text");
        if (sceneCode != null && !ArticleContentFields.SCENES.contains(sceneCode))
            ArticleBodyCodec.invalidQuery("#/sceneCode", "Unknown scene");
        var directory = sceneCode == null ? List.<ArticleCategory>of() : categories.selectList(new LambdaQueryWrapper<ArticleCategory>());
        List<Long> roots = sceneCode == null ? null : sceneRoots(directory, sceneCode);
        var effectiveScene = roots == null ? null : directory.stream().filter(c -> roots.contains(c.getId()) || roots.contains(c.getParentId())).map(c -> Arrays.asList(c.getId(), c.getParentId())).sorted(Comparator.comparing(row -> row.get(0))).toList();
        String context = "public/" + cn.jualn.miniapp.common.constant.UserContext.getUserId() + "/" + categoryId + "/" + Boolean.TRUE.equals(includeChildren) + "/-publishedAt/" + ArticleCursor.fingerprint(java.util.Arrays.asList(q, sceneCode, effectiveScene));
        String position = ArticleCursor.decode(cursor, context);
        LocalDateTime before = null;
        Long beforeId = null;
        if (position != null) try {
            String[] parts = position.split("\\|", -1);
            if (parts.length != 2) throw new IllegalArgumentException();
            before = LocalDateTime.parse(parts[0]);
            beforeId = Long.parseLong(parts[1]);
            if (beforeId <= 0) throw new IllegalArgumentException();
        } catch (IllegalArgumentException e) {
            throw ArticleCursor.invalid();
        }
        var rows = articles.publicPage(categoryId, Boolean.TRUE.equals(includeChildren), before, beforeId, size + 1, q, roots);
        boolean more = rows.size() > size;
        var page = more ? rows.subList(0, size) : rows;
        String next = null;
        if (more) {
            var last = page.get(page.size() - 1);
            next = ArticleCursor.encode(last.getPublishedAt() + "|" + last.getId(), context);
        }
        return new ArticlePageBO(summaries(page), next);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, ArticleBO> publicSummaries(List<Long> ids) {
        if (ids.isEmpty()) return Map.of();
        if (ids.size() > 50) throw new IllegalArgumentException("Bounded page required");
        return summaries(articles.publicByIds(ids)).stream().collect(java.util.stream.Collectors.toMap(ArticleBO::id, java.util.function.Function.identity()));
    }

    @Override
    public List<PublicCard> recentPublicCards(String scene, int limit) {
        var directory = categories.selectList(new LambdaQueryWrapper<ArticleCategory>());
        return articles.publicPage(null, false, null, null, limit, null, sceneRoots(directory, scene))
                .stream().map(this::publicCard).toList();
    }

    private List<Long> sceneRoots(List<ArticleCategory> directory, String scene) {
        return directory.stream()
                .filter(c -> c.getParentId() == null && Boolean.TRUE.equals(c.getEnabled())
                        && ArticleStoredFields.scenes(c.getSceneCodes(), json).contains(scene))
                .map(ArticleCategory::getId).sorted().toList();
    }

    @Override
    public PublicCard publicCard(long id, boolean lock) {
        var rows = articles.publicOptions(id, null, 0, 1, lock);
        return rows.isEmpty() ? null : publicCard(rows.get(0));
    }

    @Override
    public PublicOptions publicOptions(Long id, String titleQuery, long offset, int limit) {
        long total = articles.countPublicOptions(id, titleQuery);
        var rows = offset >= total ? List.<Article>of() : articles.publicOptions(id, titleQuery, offset, limit, false);
        return new PublicOptions(rows.stream().map(this::publicCard).toList(), total);
    }

    private PublicCard publicCard(Article row) {
        effectiveOrganization(row);
        return new PublicCard(row.getId(), row.getTitle(), row.getSummary(), row.getPublishedAt(), row.getCoverAttachmentId());
    }

    private List<ArticleBO> summaries(List<Article> page) {
        if (page.isEmpty()) return List.of();
        Map<Long, ArticleCategory> categoryMap = new HashMap<>();
        categories.selectBatchIds(page.stream().map(Article::getCategoryId).distinct().toList()).forEach(c -> categoryMap.put(c.getId(), c));
        var covers = media.batchGetAttachments(page.stream().map(Article::getCoverAttachmentId).filter(Objects::nonNull).toList());
        var statistics = interactionStatistics.counts(cn.jualn.miniapp.common.enums.TargetType.ARTICLE, page.stream().map(Article::getId).toList());
        List<ArticleBO> results = new ArrayList<>();
        var publisherIds = page.stream().map(Article::getPublisherId).filter(Objects::nonNull).distinct().toList();
        Map<Long, ArticlePublisher> publisherMap = new HashMap<>();
        if (!publisherIds.isEmpty())
            publishers.selectBatchIds(publisherIds).forEach(p -> publisherMap.put(p.getId(), p));
        for (var a : page) {
            var counts = ArticleWriter.require(statistics.get(a.getId()));
            var cover = a.getCoverAttachmentId() == null ? List.<cn.jualn.miniapp.module.media.bo.MediaAttachmentBO>of() : List.of(ArticleWriter.require(covers.get(a.getCoverAttachmentId())));
            results.add(new ArticleBO(a.getId(), a.getTitle(), a.getSummary(), a.getCategoryId(), a.getPublisherId(), a.getCoverAttachmentId(), null, null,
                    Boolean.TRUE.equals(a.getCommentsEnabled()), a.getPublishStatus(), a.getRevision(), null, null, a.getCreatedAt(), a.getUpdatedAt(), a.getPublishedAt(), a.getLastPublishedAt(),
                    categoryBO(ArticleWriter.require(categoryMap.get(a.getCategoryId()))), effectiveOrganization(a) == null ? publisherBO(ArticleWriter.require(publisherMap.get(a.getPublisherId()))) : null, new ArticleStatisticsBO(counts.viewCount(), counts.likeCount(), counts.commentCount(), counts.shareCount()), cover, effectiveOrganization(a), effectiveOrganization(a) == null ? null : a.getPublisherNameSnapshot()));
        }
        return List.copyOf(results);
    }

    @Override
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public ArticleAdminPageBO listAdmin(int page, int size, String status, Long categoryId, Boolean includeChildren, Long publisherId, String publisherOrgId, String q, String sort, boolean deleted) {
        long actor = organizations.begin("article:read");
        size(size);
        if (publisherId != null && publisherOrgId != null)
            ArticleBodyCodec.invalidQuery("#/publisherOrgId", "Publisher ID namespaces cannot be combined");
        var allowed = organizations.readable(actor);
        if (publisherOrgId != null) {
            if (publisherOrgId.isEmpty() || ArticleBodyCodec.length(publisherOrgId) > 128)
                ArticleBodyCodec.invalidQuery("#/publisherOrgId", "Invalid organization ID");
            organizations.requireReadOrganization(actor, publisherOrgId);
        }
        if (page < 1 || !Set.of("-updatedAt", "-createdAt", "-publishedAt").contains(sort) || (status != null && !Set.of("DRAFT", "PUBLISHED", "UNPUBLISHED").contains(status)))
            ArticleBodyCodec.invalidQuery("#", "Invalid list query");
        if (q != null && (q.isBlank() || ArticleBodyCodec.length(q) > 120))
            ArticleBodyCodec.invalidQuery("#/q", "Invalid search text");
        validateFilter(categoryId, includeChildren);
        var rows = articles.adminPage(status, categoryId, Boolean.TRUE.equals(includeChildren), publisherId, publisherOrgId, allowed, q, sort, (long) (page - 1) * size, size, deleted);
        return new ArticleAdminPageBO(rows.stream().map(a -> result(a, false, false)).toList(), page, size, articles.adminCount(status, categoryId, Boolean.TRUE.equals(includeChildren), publisherId, publisherOrgId, allowed, q, deleted));
    }

    @Override
    public ArticleBO create(ArticleSaveBO command) {
        organizationCapability(ArticleAuthorization.Capability.CREATE);
        validateSave(command);
        organizations.prepare("article:edit", null, command, false);
        return result(writer.create(command), false, true);
    }

    @Override
    public ArticleBO replace(long id, ArticleSaveBO command, String ifMatch) {
        long operator = organizationCapability(ArticleAuthorization.Capability.EDIT);
        Article current = ArticleWriter.require(articles.selectById(id));
        ArticleWriter.active(current);
        command = writer.preserve(command, current);
        validateSave(command);
        var proof = organizations.prepare("article:edit", id, command, "PUBLISHED".equals(current.getPublishStatus()));
        StrongEtag.requireStrict(ifMatch, "article", id, current.getRevision());
        if ("PUBLISHED".equals(current.getPublishStatus())) {
            organizationCapability(ArticleAuthorization.Capability.PUBLISH);
            checkPublication(id, command, operator, proof.freeze() ? proof.name() : null);
        }
        return result(writer.replace(id, command, ifMatch, proof.freeze() ? proof.name() : null), false, true);
    }

    @Override
    public ArticleBO publish(long id, String ifMatch) {
        long operator = organizationCapability(ArticleAuthorization.Capability.PUBLISH);
        Article current = ArticleWriter.require(articles.selectById(id));
        ArticleWriter.active(current);
        var proof = organizations.prepare("article:publish", id, null, true);
        StrongEtag.requireStrict(ifMatch, "article", id, current.getRevision());
        if ("PUBLISHED".equals(current.getPublishStatus()))
            throw ContractProblemException.conflict("lifecycle-conflict", "Article is already published");
        checkPublication(id, new ArticleSaveBO(current.getTitle(), current.getSummary(), current.getCategoryId(), current.getPublisherId(), current.getCoverAttachmentId(), body(current), current.getCommentsEnabled(), current.getPublisherOrgId(), ArticleStoredFields.files(current, json), ArticleStoredFields.read(current.getSource(), json), true), operator, proof.name());
        return result(writer.transition(id, ifMatch, true, proof.name()), false, true);
    }

    @Override
    public ArticleBO unpublish(long id, String ifMatch) {
        return unpublish(id, ifMatch, "NORMAL", null);
    }

    @Override
    public ArticleBO unpublish(long id, String ifMatch, String mode, String reason) {
        organizationCapability(ArticleAuthorization.Capability.UNPUBLISH);
        return result(writer.unpublish(id, ifMatch, mode, reason), false, true);
    }

    @Override
    public void delete(long id, String ifMatch) {
        writer.deletion(id, ifMatch, false);
    }

    @Override
    public ArticleBO restore(long id, String ifMatch) {
        return result(writer.deletion(id, ifMatch, true), false, true);
    }

    @Override
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public Usage learningTaskUsage(long id) {
        getAdmin(id);
        long actor = organizations.begin("article:read");
        var row = ArticleWriter.require(articles.selectById(id));
        boolean references = taskUsage.hasReferences(id);
        boolean eligible = false;
        if (row.getDeletedAt() == null && !"PUBLISHED".equals(row.getPublishStatus())) {
            try {
                writer.deletionProof(actor, row);
                eligible = true;
            } catch (ContractProblemException e) {
                if (e.getStatus().is5xxServerError()) throw e;
            }
        }
        boolean active = taskUsage.hasActiveReferences(id), canUnpublish = false;
        if (row.getDeletedAt() == null && "PUBLISHED".equals(row.getPublishStatus())) {
            try {
                organizations.check(actor, "article:unpublish", row, null, false);
                canUnpublish = !active;
            } catch (ContractProblemException e) {
                if (e.getStatus().is5xxServerError()) throw e;
            }
        }
        return new Usage(references, eligible && !references, active, canUnpublish);
    }

    @Override
    public List<ArticleCategoryBO> categories(boolean controlled) {
        if (controlled) authorization.require(ArticleAuthorization.Capability.CATEGORY_READ);
        var directory = categories.selectList(new LambdaQueryWrapper<ArticleCategory>().orderByAsc(ArticleCategory::getDisplayOrder, ArticleCategory::getId));
        Set<Long> enabledRoots = new HashSet<>();
        directory.stream().filter(c -> c.getParentId() == null && Boolean.TRUE.equals(c.getEnabled())).forEach(c -> enabledRoots.add(c.getId()));
        return directory.stream().filter(c -> controlled || (Boolean.TRUE.equals(c.getEnabled()) && (c.getParentId() == null || enabledRoots.contains(c.getParentId())))).map(this::categoryBO).toList();
    }

    @Override
    public ArticleCategoryBO category(long id) {
        authorization.require(ArticleAuthorization.Capability.CATEGORY_READ);
        return categoryBO(ArticleWriter.require(categories.selectById(id)));
    }

    @Override
    public ArticleCategoryBO createCategory(ArticleCategorySaveBO command) {
        return saveCategory(null, command, null);
    }

    @Override
    public ArticleCategoryBO replaceCategory(long id, ArticleCategorySaveBO command, String ifMatch) {
        return saveCategory(id, command, ifMatch);
    }

    private ArticleCategoryBO saveCategory(Long id, ArticleCategorySaveBO command, String ifMatch) {
        long operator = authorization.require(ArticleAuthorization.Capability.CATEGORY_MANAGE);
        if (command == null || command.name() == null || ArticleBodyCodec.blank(command.name()) || ArticleBodyCodec.length(command.name()) > 60
                || command.displayOrder() == null || command.displayOrder() < 0 || command.displayOrder() > 100000 || command.enabled() == null)
            ArticleBodyCodec.invalid("#", "Invalid category");
        if (id != null) {
            var current = ArticleWriter.require(categories.selectById(id));
            StrongEtag.requireStrict(ifMatch, "article-category", id, current.getRevision());
        }
        safety.text(operator, ArticleBodyCodec.trim(command.name()));
        return categoryBO(writer.saveCategory(id, command, ifMatch));
    }

    @Override
    public List<ArticlePublisherBO> publishers() {
        authorization.require(ArticleAuthorization.Capability.PUBLISHER_READ);
        return publishers.selectList(new LambdaQueryWrapper<cn.jualn.miniapp.module.article.entity.ArticlePublisher>().orderByAsc(cn.jualn.miniapp.module.article.entity.ArticlePublisher::getId)).stream().map(this::publisherBO).toList();
    }

    @Override
    public ArticlePublisherBO publisher(long id) {
        authorization.require(ArticleAuthorization.Capability.PUBLISHER_READ);
        return publisherBO(ArticleWriter.require(publishers.selectById(id)));
    }

    @Override
    public ArticlePublisherBO replacePublisher(long id, String name, String ifMatch) {
        long operator = authorization.require(ArticleAuthorization.Capability.PUBLISHER_MANAGE);
        if (name == null || ArticleBodyCodec.blank(name) || ArticleBodyCodec.length(name) > 120)
            ArticleBodyCodec.invalid("#/displayName", "Invalid publisher name");
        var current = ArticleWriter.require(publishers.selectById(id));
        StrongEtag.requireStrict(ifMatch, "article-publisher", id, current.getRevision());
        safety.text(operator, ArticleBodyCodec.trim(name));
        return publisherBO(writer.renamePublisher(id, name, ifMatch));
    }

    private long organizationCapability(ArticleAuthorization.Capability capability) {
        try {
            return authorization.require(capability);
        } catch (ContractProblemException failure) {
            if (failure.getStatus() == org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE)
                throw ArticleOrganizationPolicy.unavailable();
            throw failure;
        }
    }

    private void validateSave(ArticleSaveBO command) {
        if (command == null || command.title() == null || ArticleBodyCodec.length(command.title()) > 120 || command.summary() == null || ArticleBodyCodec.length(command.summary()) > 500 || (command.publisherId() == null) == (command.publisherOrgId() == null))
            ArticleBodyCodec.invalid("#", "Invalid Article content");
        if (command.attachmentIds() != null)
            ArticleContentFields.files(json.valueToTree(command.attachmentIds().stream().map(String::valueOf).toList()));
        ArticleContentFields.source(command.source());
        if (command.attachmentIds() != null && !command.attachmentIds().isEmpty() || !codec.validate(command.body(), command.coverAttachmentId()).references().isEmpty())
            organizationCapability(ArticleAuthorization.Capability.MEDIA);
    }

    private void checkPublication(long articleId, ArticleSaveBO command, long operator, String signature) {
        var document = codec.validate(command.body(), command.coverAttachmentId());
        if (!document.references().isEmpty()) organizationCapability(ArticleAuthorization.Capability.MEDIA_READ);
        var category = command.categoryId() == null ? null : categories.selectById(command.categoryId());
        List<ContractProblemException.Violation> errors = new ArrayList<>();
        if (ArticleBodyCodec.blank(command.title()))
            errors.add(new ContractProblemException.Violation("body", "#/title", "INVALID_VALUE", "Title must not be blank"));
        if (!document.hasContent())
            errors.add(new ContractProblemException.Violation("body", "#/body", "INVALID_VALUE", "Body must contain text or media"));
        var parent = category == null || category.getParentId() == null ? null : categories.selectById(category.getParentId());
        if (category == null || !Boolean.TRUE.equals(category.getEnabled()) || (category.getParentId() != null && (parent == null || !Boolean.TRUE.equals(parent.getEnabled()))))
            errors.add(new ContractProblemException.Violation("body", "#/categoryId", "INVALID_REFERENCE", "Use an enabled category"));
        if (!errors.isEmpty())
            throw ContractProblemException.conflict("publish-validation-failed", "Publication validation failed", errors);
        List<cn.jualn.miniapp.module.media.bo.MediaAttachmentBO> attachments;
        try {
            attachments = media.requireArticleAttachments(document.references());
        } catch (ContractProblemException e) {
            if ("/problems/validation-error".equals(e.getType()))
                throw ContractProblemException.conflict("publish-validation-failed", "Publication media validation failed", e.getErrors());
            throw e;
        }
        var fileAttachments = articleMedia.requireDocuments(command.attachmentIds() == null ? List.of() : command.attachmentIds());
        if (!fileAttachments.isEmpty()) organizationCapability(ArticleAuthorization.Capability.MEDIA_READ);
        String fileText = articleMedia.publicationDocumentText(fileAttachments);
        String sourceText = command.source() == null ? "" : command.source().toString();
        String mediaNames = attachments.stream().map(a -> a.getOriginalName()).filter(Objects::nonNull).collect(java.util.stream.Collectors.joining("\n"));
        safety.text(operator, command.title() + "\n" + command.summary() + "\n" + document.safetyText() + "\n" + fileText + "\n" + sourceText + "\n" + mediaNames + (signature == null ? "" : "\n" + signature));
        safety.media(operator, articleId, attachments);
    }

    private ArticleBO result(Article a, boolean publicRead, boolean detail) {
        var refs = publicRead ? media.listLinkedAttachments(cn.jualn.miniapp.common.enums.TargetType.ARTICLE, a.getId()) : List.<cn.jualn.miniapp.module.media.bo.MediaAttachmentBO>of();
        return new ArticleBO(a.getId(), a.getTitle(), a.getSummary(), a.getCategoryId(), a.getPublisherId(), a.getCoverAttachmentId(), detail ? body(a) : null, a.getPlainText(), Boolean.TRUE.equals(a.getCommentsEnabled()), a.getPublishStatus(), a.getRevision(), a.getCreatedBy(), a.getUpdatedBy(), a.getCreatedAt(), a.getUpdatedAt(), a.getPublishedAt(), a.getLastPublishedAt(),
                publicRead ? categoryBO(ArticleWriter.require(categories.selectById(a.getCategoryId()))) : null, publicRead && effectiveOrganization(a) == null ? publisherBO(ArticleWriter.require(publishers.selectById(a.getPublisherId()))) : null,
                publicRead ? factStatistics(a.getId()) : null, refs, effectiveOrganization(a), effectiveOrganization(a) == null ? null : a.getPublisherNameSnapshot(), ArticleStoredFields.files(a, json), ArticleStoredFields.read(a.getSource(), json), a.getDeletedAt(), a.getDeletedBy());
    }

    private String effectiveOrganization(Article a) {
        boolean represented = a.getOrganizationRepresentation() != null ? a.getOrganizationRepresentation() : organizations.organizationRepresentation(a);
        if (!represented && a.getPublisherId() == null) throw ArticleOrganizationPolicy.unavailable();
        return represented ? a.getPublisherOrgId() : null;
    }

    private com.fasterxml.jackson.databind.JsonNode body(Article a) {
        try {
            return json.readTree(a.getBody());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Stored Article body is invalid", e);
        }
    }

    private ArticleCategoryBO categoryBO(ArticleCategory a) {
        return new ArticleCategoryBO(a.getId(), a.getParentId(), a.getName(), a.getDisplayOrder(), Boolean.TRUE.equals(a.getEnabled()), a.getRevision(), a.getCreatedAt(), a.getUpdatedAt(), ArticleStoredFields.scenes(a.getSceneCodes(), json));
    }

    private ArticlePublisherBO publisherBO(ArticlePublisher a) {
        return new ArticlePublisherBO(a.getId(), a.getDisplayName(), a.getRevision(), a.getUpdatedAt(), a.getUpdatedBy());
    }

    private void validateFilter(Long categoryId, Boolean includeChildren) {
        if (categoryId == null && includeChildren != null)
            ArticleBodyCodec.invalidQuery("#/includeChildren", "categoryId is required with includeChildren");
        if (categoryId != null) ArticleWriter.require(categories.selectById(categoryId));
    }

    private void size(int size) {
        if (size < 1 || size > 50) ArticleBodyCodec.invalidQuery("#/pageSize", "pageSize must be 1..50");
    }
}
