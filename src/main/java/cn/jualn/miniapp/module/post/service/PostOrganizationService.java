package cn.jualn.miniapp.module.post.service;

import cn.jualn.miniapp.module.post.PostType;
import cn.jualn.miniapp.module.post.PostProblems;
import cn.jualn.miniapp.module.post.bo.PostCategoryBO;
import cn.jualn.miniapp.module.post.bo.PostDictionaryPageBO;
import cn.jualn.miniapp.module.post.bo.PostDictionarySaveBO;
import cn.jualn.miniapp.module.post.bo.PostTopicBO;
import cn.jualn.miniapp.module.post.bo.PostVersionedBO;
import cn.jualn.miniapp.module.post.entity.Post;
import cn.jualn.miniapp.module.post.entity.PostCategoryType;
import cn.jualn.miniapp.module.post.entity.PostDictionary;
import cn.jualn.miniapp.module.post.entity.PostTopicLink;
import cn.jualn.miniapp.module.post.mapper.PostOrganizationMapper;
import cn.jualn.miniapp.module.admin.operation.service.AdminOperationLogService;
import cn.jualn.miniapp.common.exception.ContractProblemException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PostOrganizationService {
    private final PostDictionaryAuthorization authorization;
    private final PostOrganizationMapper mapper;
    private final PostRepresentationTags tags;
    private final PostCursorCodec cursors;
    private final AdminOperationLogService audit;

    public record Projection(Map<Long, PostCategoryBO> categories, Map<Long, List<PostTopicBO>> topics,
                             Map<Long, List<Long>> topicIds) {
    }

    public Projection project(Collection<Long> postIds, Collection<Long> categoryIds) {
        var categories = categoryIds.isEmpty() ? List.<PostDictionary>of() : mapper.categories(categoryIds);
        var types = categoryIds.isEmpty() ? List.<PostCategoryType>of() : mapper.categoryTypes(categoryIds);
        var categoryMap = categories.stream().collect(Collectors.toMap(PostDictionary::getId, d -> category(d, types)));
        var links = postIds.isEmpty() ? List.<PostTopicLink>of() : mapper.links(postIds);
        var ids = links.stream().map(PostTopicLink::getTopicId).collect(Collectors.toSet());
        var topics = ids.isEmpty() ? Map.<Long, PostTopicBO>of() : mapper.topics(ids).stream().collect(Collectors.toMap(PostDictionary::getId, this::topic));
        Map<Long, List<PostTopicBO>> topicMap = new HashMap<>();
        Map<Long, List<Long>> idMap = new HashMap<>();
        for (var link : links) {
            var topic = topics.get(link.getTopicId());
            if (topic == null) {
                throw new IllegalStateException("Missing referenced topic");
            }
            topicMap.computeIfAbsent(link.getPostId(), k -> new ArrayList<>()).add(topic);
            idMap.computeIfAbsent(link.getPostId(), k -> new ArrayList<>()).add(link.getTopicId());
        }
        return new Projection(categoryMap, topicMap, idMap);
    }

    /**
     * Caller owns the Post transaction. Sorted dictionary locks serialize new references with disable.
     */
    public void validateReferences(String type, Long categoryId, List<Long> topicIds) {
        if ("MENTOR_QA".equals(type) && categoryId == null) {
            throw categoryInvalid();
        }
        if (categoryId != null) {
            var d = mapper.lockCategory(categoryId);
            if (d == null || !"ACTIVE".equals(d.getStatus()) || mapper.lockCategoryTypes(categoryId).stream().noneMatch(t -> type.equals(t.getPostType()))) {
                throw categoryInvalid();
            }
        }
        if (topicIds != null) {
            if (topicIds.size() > 3 || topicIds.stream().anyMatch(Objects::isNull) || new HashSet<>(topicIds).size() != topicIds.size()) {
                throw topicInvalid();
            }
            for (Long id : topicIds.stream().sorted().toList()) {
                var d = mapper.lockTopic(id);
                if (d == null || !"ACTIVE".equals(d.getStatus())) {
                    throw topicInvalid();
                }
            }
        }
    }

    public void replaceTopics(long postId, List<Long> topicIds) {
        mapper.clearTopics(postId);
        for (int i = 0; i < topicIds.size(); i++) {
            mapper.link(postId, topicIds.get(i), i);
        }
    }

    public void validateFilter(Long categoryId, Long topicId) {
        if (categoryId != null && mapper.categories(List.of(categoryId)).isEmpty()) {
            throw categoryInvalid();
        }
        if (topicId != null && mapper.topics(List.of(topicId)).isEmpty()) {
            throw topicInvalid();
        }
    }

    public PostDictionaryPageBO<PostCategoryBO> categories(String status, String type, String cursor, int size) {
        validateQuery(status, type, size);
        String query = "categories:" + status + ":" + type;
        var anchor = cursors.decode(cursor, query);
        var rows = mapper.pageCategories(status, type, anchor == null ? null : anchor.order(), anchor == null ? null : anchor.id(), size + 1);
        boolean more = rows.size() > size;
        rows = rows.stream().limit(size).toList();
        var types = rows.isEmpty() ? List.<PostCategoryType>of() : mapper.categoryTypes(rows.stream().map(PostDictionary::getId).toList());
        return new PostDictionaryPageBO<>(rows.stream().map(d -> category(d, types)).toList(), next(rows, more, query));
    }

    public PostDictionaryPageBO<PostTopicBO> topics(String status, String cursor, int size) {
        validateQuery(status, null, size);
        String query = "topics:" + status;
        var anchor = cursors.decode(cursor, query);
        var rows = mapper.pageTopics(status, anchor == null ? null : anchor.order(), anchor == null ? null : anchor.id(), size + 1);
        boolean more = rows.size() > size;
        rows = rows.stream().limit(size).toList();
        return new PostDictionaryPageBO<>(rows.stream().map(this::topic).toList(), next(rows, more, query));
    }

    private String next(List<PostDictionary> rows, boolean more, String query) {
        if (!more) {
            return null;
        }
        var last = rows.get(rows.size() - 1);
        return cursors.encode(query, last.getId(), last.getDisplayOrder());
    }

    private void validateQuery(String status, String type, int size) {
        if (status == null || !Set.of("ACTIVE", "INACTIVE", "ALL").contains(status) || size < 1 || size > 50) {
            throw PostProblems.invalid("query", "INVALID_VALUE", "Invalid dictionary query");
        }
        if (type != null) {
            parseType(type);
        }
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public PostVersionedBO<?> get(boolean category, long id) {
        authorization.read();
        var row = category ? mapper.lockCategory(id) : mapper.lockTopic(id);
        if (row == null) {
            throw notFound();
        }
        return version(category, row);
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public PostVersionedBO<?> create(boolean category, PostDictionarySaveBO command, long operator) {
        authorization.write(operator);
        validateSave(category, command, true);
        var d = new PostDictionary();
        d.setCode(command.code());
        d.setName(command.name());
        d.setStatus(command.status() == null ? "ACTIVE" : command.status());
        d.setDisplayOrder(command.displayOrder() == null ? 0 : command.displayOrder());
        d.setRevision(0L);
        try {
            if (category) {
                mapper.createCategory(d);
            } else {
                mapper.createTopic(d);
            }
        } catch (DuplicateKeyException failure) {
            throw ContractProblemException.conflict("post-dictionary-conflict", "Dictionary code already exists");
        }
        if (category) {
            for (String type : command.postTypes()) {
                mapper.addType(d.getId(), type);
            }
        }
        var result = version(category, d);
        audit.recordPostDictionaryChange(operator, category ? "post-category" : "topic", d.getId(), null, result.etag());
        return result;
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public PostVersionedBO<?> edit(boolean category, long id, PostDictionarySaveBO command, String ifMatch, long operator) {
        authorization.write(operator);
        var d = category ? mapper.lockCategory(id) : mapper.lockTopic(id);
        if (d == null) {
            throw notFound();
        }
        var before = version(category, d);
        PostProblems.match(ifMatch, before.etag());
        validateSave(category, command, false);
        if (command.fields().contains("name")) {
            d.setName(command.name());
        }
        if (command.fields().contains("status")) {
            d.setStatus(command.status());
        }
        if (command.fields().contains("displayOrder")) {
            d.setDisplayOrder(command.displayOrder());
        }
        if (category && command.fields().contains("postTypes")) {
            var old = mapper.lockCategoryTypes(id).stream().map(PostCategoryType::getPostType).toList();
            for (String type : old) {
                if (!command.postTypes().contains(type)) {
                    if (mapper.historicalReference(id, type) != null) {
                        throw ContractProblemException.conflict("post-dictionary-conflict", "Category applicability is referenced by historical posts");
                    }
                    mapper.removeType(id, type);
                }
            }
            for (String type : command.postTypes()) {
                if (!old.contains(type)) {
                    mapper.addType(id, type);
                }
            }
        }
        if (category) {
            mapper.updateCategory(d);
        } else {
            mapper.updateTopic(d);
        }
        d.setRevision(d.getRevision() + 1);
        var result = version(category, d);
        audit.recordPostDictionaryChange(operator, category ? "post-category" : "topic", id, before.etag(), result.etag());
        return result;
    }

    private void validateSave(boolean category, PostDictionarySaveBO c, boolean create) {
        if (c == null || c.fields().isEmpty()) {
            throw PostProblems.invalid("body", "REQUIRED", "Provide dictionary fields");
        }
        if (create && (c.code() == null || !c.code().matches("[A-Z][A-Z0-9_]{0,63}"))) {
            throw PostProblems.invalid("code", "INVALID_VALUE", "Invalid code");
        }
        if (!create && c.fields().contains("code")) {
            throw PostProblems.invalid("code", "INVALID_VALUE", "Code is immutable");
        }
        if ((create || c.fields().contains("name")) && (c.name() == null || c.name().isBlank() || c.name().length() > 64)) {
            throw PostProblems.invalid("name", "INVALID_VALUE", "Invalid name");
        }
        if (c.fields().contains("status") && (c.status() == null || !Set.of("ACTIVE", "INACTIVE").contains(c.status()))) {
            throw PostProblems.invalid("status", "INVALID_VALUE", "Invalid state");
        }
        if (c.fields().contains("displayOrder") && (c.displayOrder() == null || c.displayOrder() < 0)) {
            throw PostProblems.invalid("displayOrder", "INVALID_VALUE", "Invalid order");
        }
        if (!category && c.fields().contains("postTypes")) {
            throw PostProblems.invalid("postTypes", "INVALID_VALUE", "Topics have no post type");
        }
        if (category && (create || c.fields().contains("postTypes"))) {
            if (c.postTypes() == null || c.postTypes().isEmpty() || new HashSet<>(c.postTypes()).size() != c.postTypes().size()) {
                throw PostProblems.invalid("postTypes", "INVALID_VALUE", "Provide unique supported types");
            }
            c.postTypes().forEach(this::parseType);
        }
    }

    private void parseType(String type) {
        try {
            PostType.valueOf(type);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw PostProblems.invalid("postType", "INVALID_VALUE", "Unsupported post type");
        }
    }

    private PostVersionedBO<?> version(boolean category, PostDictionary d) {
        Object value = category ? category(d, mapper.lockCategoryTypes(d.getId())) : topic(d);
        return new PostVersionedBO<>(value, tags.tag(value, d.getRevision()));
    }

    private PostCategoryBO category(PostDictionary d, List<PostCategoryType> types) {
        return new PostCategoryBO(d.getId(), d.getCode(), d.getName(), types.stream().filter(t -> d.getId().equals(t.getCategoryId())).map(PostCategoryType::getPostType).sorted().toList(), d.getStatus(), d.getDisplayOrder());
    }

    private PostTopicBO topic(PostDictionary d) {
        return new PostTopicBO(d.getId(), d.getCode(), d.getName(), d.getStatus(), d.getDisplayOrder());
    }

    private ContractProblemException notFound() {
        return PostProblems.problem(HttpStatus.NOT_FOUND, "resource-not-found", "Dictionary item not found");
    }

    private ContractProblemException categoryInvalid() {
        return PostProblems.problem(HttpStatus.BAD_REQUEST, "post-category-invalid", "Category is required, active and applicable");
    }

    private ContractProblemException topicInvalid() {
        return PostProblems.problem(HttpStatus.BAD_REQUEST, "post-topic-invalid", "Select at most three distinct active topics");
    }
}
