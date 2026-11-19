package cn.jualn.miniapp.module.article.service;

import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.common.web.StrongEtag;
import cn.jualn.miniapp.module.admin.operation.service.AdminAuditJournal;
import cn.jualn.miniapp.module.article.bo.*;
import cn.jualn.miniapp.module.article.entity.*;
import cn.jualn.miniapp.module.article.mapper.*;
import cn.jualn.miniapp.module.media.bo.AttachmentLinkBO;
import cn.jualn.miniapp.module.media.service.MediaQueries;
import cn.jualn.miniapp.module.media.service.MediaService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import java.time.*;
import java.util.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Short local transactions recheck mutable eligibility after candidate safety checks.
 */
@Service
@RequiredArgsConstructor
public class ArticleWriter {
    @Autowired
    private AdminAuditJournal managementAudit;

    private final ArticleMapper articles;
    @Autowired private cn.jualn.miniapp.module.learningtask.service.LearningTaskArticleUsage taskUsage;
    private final ArticleCategoryMapper categories;
    private final ArticlePublisherMapper publishers;
    private final MediaService media;
    private final MediaQueries mediaQueries;
    @Autowired private cn.jualn.miniapp.module.media.service.ArticleMediaService articleMedia;
    private final ArticleAuthorization authorization;
    private final ArticleBodyCodec codec;
    private final ArticleOrganizationPolicy organizations;
    private final ObjectMapper json;

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.REPEATABLE_READ)
    public Article create(ArticleSaveBO command) {
        long operator = organizations.begin("article:edit");
        var proof = organizations.check(operator, "article:edit", null, command, false);
        require(articles.lockGuard());
        eligibility(command, false, operator);
        Article entity = new Article();
        entity.setPublishStatus("DRAFT");
        entity.setCommentsEnabled(true);
        apply(entity, command, operator);
        entity.setPublisherOrgId(proof.organizationId());
        entity.setCreatedBy(operator);
        entity.setCreatedAt(entity.getUpdatedAt());
        entity.setRevision(1L);
        if (articles.insert(entity) != 1) throw new IllegalStateException("Article insert failed");
        links(entity, command);
        organizations.audit(null, entity, "CREATE", proof, null);
        return entity;
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.REPEATABLE_READ)
    public Article replace(long id, ArticleSaveBO command, String ifMatch, String expectedName) {
        long operator = organizations.begin("article:edit");
        // Article-owned guard preserves taxonomy/publication serialization without any Publisher row.
        require(articles.lockGuard());
        Article entity = require(articles.lock(id));
        var before = copy(entity);
        active(entity);
        var proof = organizations.check(operator, "article:edit", before, command, "PUBLISHED".equals(entity.getPublishStatus()));
        managementAudit.confirmed(operator, "replaceArticle", "ARTICLE", Long.toString(id));
        StrongEtag.requireStrict(ifMatch, "article", id, entity.getRevision());
        command=preserve(command,entity);
        eligibility(command, "PUBLISHED".equals(entity.getPublishStatus()), operator);
        apply(entity, command, operator);
        entity.setPublisherOrgId(proof.organizationId());
        freeze(entity, proof, expectedName);
        entity.setRevision(entity.getRevision() + 1);
        if (articles.replace(entity) != 1) throw ContractProblemException.preconditionFailed();
        links(entity, command);
        organizations.audit(before, entity, "REPLACE", proof, expectedName);
        return entity;
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.REPEATABLE_READ)
    public Article transition(long id, String ifMatch, boolean publish, String expectedName) {
        return transition(id,ifMatch,publish,expectedName,"NORMAL",null);
    }
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.REPEATABLE_READ)
    public Article unpublish(long id,String ifMatch,String mode,String reason) {
        if(!Set.of("NORMAL","SAFETY").contains(mode==null?"":mode) ||
            (reason!=null && (reason.isBlank() || reason.codePointCount(0,reason.length())>1000)) ||
            ("SAFETY".equals(mode) && reason==null)) ArticleBodyCodec.invalid("#/reason","Valid unpublish mode and safety reason are required");
        return transition(id,ifMatch,false,null,mode,reason);
    }
    private Article transition(long id,String ifMatch,boolean publish,String expectedName,String mode,String reason) {
        long operator = organizations.begin(publish ? "article:publish" : "article:unpublish");
        require(articles.lockGuard());
        Article entity = require(articles.lock(id));
        var before = copy(entity);
        active(entity);
        var proof = organizations.check(operator, publish ? "article:publish" : "article:unpublish", before, null, publish);
        managementAudit.confirmed(operator, publish ? "publishArticle" : "unpublishArticle", "ARTICLE", Long.toString(id));
        StrongEtag.requireStrict(ifMatch, "article", id, entity.getRevision());
        if (publish == "PUBLISHED".equals(entity.getPublishStatus()))
            throw ContractProblemException.conflict("lifecycle-conflict", "Operation is not allowed in the current state");
        if(!publish && "NORMAL".equals(mode) && taskUsage.hasActiveReferences(id))
            throw ContractProblemException.conflict("article-in-use","A published LearningTask references this Article");
        if (publish) {
            eligibility(new ArticleSaveBO(entity.getTitle(), entity.getSummary(), entity.getCategoryId(), entity.getPublisherId(),
                    entity.getCoverAttachmentId(), readBody(entity), entity.getCommentsEnabled(), entity.getPublisherOrgId(),ArticleStoredFields.files(entity,json),ArticleStoredFields.read(entity.getSource(),json),true), true, operator);
            freeze(entity, proof, expectedName);
            if (entity.getPublishedAt() == null) entity.setPublishedAt(now());
            entity.setLastPublishedAt(now());
        }
        entity.setPublishStatus(publish ? "PUBLISHED" : "UNPUBLISHED");
        entity.setUpdatedBy(operator);
        entity.setUpdatedAt(now());
        entity.setRevision(entity.getRevision() + 1);
        if (articles.replace(entity) != 1) throw ContractProblemException.preconditionFailed();
        organizations.audit(before, entity, publish ? "PUBLISH" : "UNPUBLISH", proof, expectedName);
        if(!publish && "SAFETY".equals(mode) && articles.safetyUnpublish(id,entity.getRevision(),operator,reason,now())!=1)
            throw new IllegalStateException("Safety unpublish audit was not accepted");
        return entity;
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public ArticleCategory saveCategory(Long id, ArticleCategorySaveBO command, String ifMatch) {
        long operator = authorization.require(ArticleAuthorization.Capability.CATEGORY_MANAGE);
        require(articles.lockGuard());
        ArticleCategory value = id == null ? new ArticleCategory() : require(categories.lock(id));
        if (id != null) StrongEtag.requireStrict(ifMatch, "article-category", id, value.getRevision());
        if (command.parentId() != null) {
            if (id != null && !Objects.equals(command.parentId(), value.getParentId()))
                ArticleBodyCodec.invalid("#/parentId", "Parent relationship is immutable");
            ArticleCategory parent = categories.lock(command.parentId());
            if (parent == null || parent.getParentId() != null || Objects.equals(id, command.parentId()))
                ArticleBodyCodec.invalid("#/parentId", "Parent must be an existing top-level category");
            if (id == null) value.setParentId(parent.getId());
        }
        if (id == null && categories.selectCount(new LambdaQueryWrapper<>()) >= 100)
            throw ContractProblemException.conflict("category-limit-reached", "At most 100 categories are allowed");
        if (command.sceneCodes() != null) {
            var currentScenes = ArticleStoredFields.scenes(value.getSceneCodes(), json);
            if (!currentScenes.equals(command.sceneCodes())) {
                ArticleBodyCodec.invalid("#/sceneCodes", "Scene bindings are fixed by the category initialization");
            }
        }
        value.setName(ArticleBodyCodec.trim(command.name()));
        value.setDisplayOrder(command.displayOrder());
        value.setEnabled(command.enabled());
        value.setUpdatedAt(now());
        value.setUpdatedBy(operator);
        if (id == null) {
            value.setCreatedAt(value.getUpdatedAt());
            value.setRevision(1L);
            value.setCreatedBy(operator);
        } else value.setRevision(value.getRevision() + 1);
        try {
            if ((id == null ? categories.insert(value) : categories.updateById(value)) != 1)
                throw new IllegalStateException("Category write failed");
        } catch (DuplicateKeyException e) {
            throw ContractProblemException.conflict("category-name-conflict", "Category name already exists");
        }
        managementAudit.operation(java.util.UUID.randomUUID().toString(), operator, id == null ? "createArticleCategory" : "replaceArticleCategory", "ARTICLE_CATEGORY", String.valueOf(value.getId()), java.time.LocalDateTime.now(java.time.ZoneOffset.UTC));
        return value;
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public ArticlePublisher renamePublisher(long id, String name, String ifMatch) {
        long operator = authorization.require(ArticleAuthorization.Capability.PUBLISHER_MANAGE);
        require(articles.lockGuard());
        ArticlePublisher value = require(publishers.lock(id));
        if (articles.legacyReferences(id) > 0) throw ArticleOrganizationPolicy.migration();
        StrongEtag.requireStrict(ifMatch, "article-publisher", id, value.getRevision());
        value.setDisplayName(ArticleBodyCodec.trim(name));
        value.setRevision(value.getRevision() + 1);
        value.setUpdatedBy(operator);
        value.setUpdatedAt(now());
        if (publishers.updateById(value) != 1) throw new IllegalStateException("Publisher write failed");
        return value;
    }

    /** The same Article guard is held by every new task reference check until commit. */
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public Article deletion(long id,String ifMatch,boolean restore) {
        long operator=organizations.beginDeletion();
        require(articles.lockGuard());
        Article entity=require(articles.lock(id));
        var before=copy(entity);
        var proof=deletionProof(operator,entity);
        managementAudit.confirmed(operator,restore?"restoreArticle":"deleteArticle","ARTICLE",Long.toString(id));
        StrongEtag.requireStrict(ifMatch,"article",id,entity.getRevision());
        if("PUBLISHED".equals(entity.getPublishStatus())) throw ContractProblemException.conflict("lifecycle-conflict","Unpublish the Article first");
        if(restore && entity.getDeletedAt()==null) throw ContractProblemException.conflict("lifecycle-conflict","Article is not deleted");
        if(!restore && entity.getDeletedAt()!=null) return entity;
        if(!restore && taskUsage.hasReferences(id)) throw ContractProblemException.conflict("article-in-use","A retained LearningTask references this Article");
        entity.setDeletedAt(restore?null:now()); entity.setDeletedBy(restore?null:operator);
        entity.setRevision(entity.getRevision()+1); entity.setUpdatedAt(now()); entity.setUpdatedBy(operator);
        if(articles.replace(entity)!=1) throw ContractProblemException.preconditionFailed();
        organizations.audit(before,entity,restore?"RESTORE":"DELETE",proof,null);
        return entity;
    }
    public ArticleOrganizationPolicy.Prepared deletionProof(long actor,Article row) {
        boolean draft="DRAFT".equals(row.getPublishStatus()) && row.getPublishedAt()==null;
        if(!draft) organizations.require(actor,"article:unpublish");
        return organizations.check(actor,draft?"article:edit":"article:publish",row,null,false);
    }
    public static void active(Article row) {
        if(row.getDeletedAt()!=null) throw ContractProblemException.conflict("lifecycle-conflict","Article is logically deleted");
    }

    private void eligibility(ArticleSaveBO command, boolean publishing, long operator) {
        ArticleCategory category = command.categoryId() == null ? null : categories.lock(command.categoryId());
        if (command.categoryId() != null && category == null) invalidReference("#/categoryId");
        ArticleCategory parent = category == null || category.getParentId() == null ? null : categories.lock(category.getParentId());
        var document = codec.validate(command.body(), command.coverAttachmentId());
        if (publishing && !document.references().isEmpty()) organizations.require(operator, "media:read");
        mediaQueries.requireArticleAttachments(document.references());
        var files=command.attachmentIds()==null?List.<Long>of():command.attachmentIds();
        ArticleContentFields.files(json.valueToTree(files.stream().map(String::valueOf).toList())); ArticleContentFields.source(command.source());
        if(!files.isEmpty()) {organizations.require(operator,publishing?"media:read":"media:write");articleMedia.requireDocuments(files);}
        if (publishing) {
            List<ContractProblemException.Violation> errors = new ArrayList<>();
            if (ArticleBodyCodec.blank(command.title()))
                errors.add(new ContractProblemException.Violation("body", "#/title", "INVALID_VALUE", "Published title must not be blank"));
            if (!document.hasContent())
                errors.add(new ContractProblemException.Violation("body", "#/body", "INVALID_VALUE", "Published body must contain text or media"));
            if (category == null || !Boolean.TRUE.equals(category.getEnabled()) || (category.getParentId() != null && (parent == null || !Boolean.TRUE.equals(parent.getEnabled()))))
                errors.add(new ContractProblemException.Violation("body", "#/categoryId", "INVALID_REFERENCE", "Use an enabled category"));
            if (!errors.isEmpty())
                throw ContractProblemException.conflict("publish-validation-failed", "Publication validation failed", errors);
        }
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.REPEATABLE_READ)
    public Article replace(long id, ArticleSaveBO command, String ifMatch) {
        return replace(id, command, ifMatch, null);
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.REPEATABLE_READ)
    public Article transition(long id, String ifMatch, boolean publish) {
        return transition(id, ifMatch, publish, null);
    }

    public ArticleSaveBO preserve(ArticleSaveBO command,Article current) {
        return new ArticleSaveBO(command.title(),command.summary(),command.categoryId(),command.publisherId(),command.coverAttachmentId(),command.body(),command.commentsEnabled(),command.publisherOrgId(),
            command.attachmentIds()==null?ArticleStoredFields.files(current,json):command.attachmentIds(),command.sourcePresent()?command.source():ArticleStoredFields.read(current.getSource(),json),true);
    }
    private Article copy(Article entity) {
        var result = new Article();
        org.springframework.beans.BeanUtils.copyProperties(entity, result);
        return result;
    }

    private void freeze(Article entity, ArticleOrganizationPolicy.Prepared proof, String expectedName) {
        if (proof.freeze()) {
            if (!Objects.equals(expectedName, proof.name())) throw ArticleOrganizationPolicy.unavailable();
            entity.setPublisherNameSnapshot(proof.name());
        }
    }

    private void links(Article entity, ArticleSaveBO command) {
        var refs = new LinkedHashMap<>(codec.validate(command.body(), command.coverAttachmentId()).references());
        ArticleStoredFields.files(entity,json).forEach(id->refs.putIfAbsent(id,"DOCUMENT"));
        List<AttachmentLinkBO> links = new ArrayList<>();
        refs.keySet().forEach(id -> links.add(new AttachmentLinkBO(id, links.size())));
        media.replaceAttachmentLinks(TargetType.ARTICLE, entity.getId(), links);
    }

    private void apply(Article entity, ArticleSaveBO command, long operator) {
        var document = codec.validate(command.body(), command.coverAttachmentId());
        if (!document.references().isEmpty() || command.attachmentIds()!=null && !command.attachmentIds().isEmpty()) organizations.require(operator, "media:write");
        if(command.attachmentIds()!=null) entity.setAttachmentIds(ArticleStoredFields.encode(command.attachmentIds().stream().map(String::valueOf).toList(),json));
        if(command.sourcePresent()) entity.setSource(ArticleStoredFields.encode(command.source(),json));
        entity.setTitle(command.title());
        entity.setSummary(command.summary());
        entity.setCategoryId(command.categoryId());
        entity.setPublisherId(command.publisherId());
        entity.setCoverAttachmentId(command.coverAttachmentId());
        entity.setBody(document.body().toString());
        entity.setPlainText(document.plainText());
        if (command.commentsEnabled() != null) entity.setCommentsEnabled(command.commentsEnabled());
        entity.setUpdatedBy(operator);
        entity.setUpdatedAt(now());
    }

    private com.fasterxml.jackson.databind.JsonNode readBody(Article entity) {
        try {
            return json.readTree(entity.getBody());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Stored Article body is invalid", e);
        }
    }

    public static LocalDateTime now() {
        return LocalDateTime.now(ZoneId.of("Asia/Shanghai")).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }

    public static <T> T require(T value) {
        if (value == null)
            throw new ContractProblemException(HttpStatus.NOT_FOUND, "/problems/not-found", "Resource is unavailable");
        return value;
    }

    private void invalidReference(String p) {
        throw ContractProblemException.validation(new ContractProblemException.Violation("body", p, "INVALID_REFERENCE", "Resource reference is unavailable"));
    }
}
