package cn.jualn.miniapp.module.post.service;

import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.module.media.enums.MediaType;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.module.media.service.MediaQueries;
import cn.jualn.miniapp.module.post.PostProblems;
import cn.jualn.miniapp.module.post.bo.PostEditBO;
import cn.jualn.miniapp.module.post.bo.PostEditableBO;
import cn.jualn.miniapp.module.post.bo.PostEffectiveEditBO;
import cn.jualn.miniapp.module.post.bo.PostAttachmentInputBO;
import cn.jualn.miniapp.module.post.bo.PostVersionedBO;
import cn.jualn.miniapp.module.post.entity.Post;
import cn.jualn.miniapp.module.post.mapper.PostMapper;

import cn.jualn.miniapp.module.media.bo.AttachmentItemBO;
import cn.jualn.miniapp.module.media.bo.MediaAttachmentSaveBO;
import cn.jualn.miniapp.module.media.service.MediaService;
import cn.jualn.miniapp.module.user.service.UserAccountService;
import cn.jualn.miniapp.module.audit.enums.AuditStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Checks outside the transaction, then locks/rechecks and commits one effective representation.
 */
@Service
@RequiredArgsConstructor
public class PostEditingService {
    @org.springframework.beans.factory.annotation.Autowired private org.springframework.beans.factory.ObjectProvider<MentorQuestions> mentorQuestions;
    private final PostMapper posts;
    private final PostPublicAccess access;
    private final PostOrganizationService organization;
    private final PostRepresentationTags tags;
    private final PostSafetyChecks safety;
    private final MediaService media;
    private final MediaQueries mediaQueries;
    private final UserAccountService users;
    private final PlatformTransactionManager transactions;

    public PostVersionedBO<PostEditableBO> get(long id) {
        return new TransactionTemplate(transactions).execute(status -> current(id));
    }

    private PostVersionedBO<PostEditableBO> current(long id) {
        var post = posts.lockPost(id);
        authorize(post);
        requireEditable(post);
        return version(post);
    }

    public PostVersionedBO<PostEditableBO> edit(long id, PostEditBO command, String ifMatch) {
        var before = get(id);
        PostProblems.match(ifMatch, before.etag());
        validate(command);
        var old = before.value();
        var fields = command.fields();
        String title = fields.contains("title") ? command.title() : old.title();
        String content = fields.contains("content") ? command.content() : old.content();
        Long category = fields.contains("categoryId") ? command.categoryId() : old.categoryId();
        List<Long> topics = fields.contains("topicIds") ? command.topicIds() : old.topicIds();
        if ("MENTOR_QA".equals(old.postType()) && category == null) {
            throw PostProblems.problem(org.springframework.http.HttpStatus.BAD_REQUEST, "post-category-invalid", "A mentor question requires a category");
        }
        List<AttachmentItemBO> candidate = fields.contains("attachmentItems") ? media.prepareAttachments(TargetType.POST, id, command.attachmentItems()) : old.attachmentItems().stream().map(PostAttachmentInputBO::input).toList();
        if ("NORMAL".equals(old.postType())) PostContentRules.validate(content,candidate);
        else if(content==null || content.isBlank())throw PostProblems.invalid("content","REQUIRED","A question requires text");
        long actor = requireUser();
        if("NORMAL".equals(old.postType())) users.assertCommunityContentAllowed(actor); else users.assertContentCreationAllowed(actor);
        var changedMedia = candidate.stream().filter(item -> old.attachmentItems().stream().noneMatch(previous ->
                Objects.equals(previous.type(), item.getType()) && Objects.equals(previous.objectKey(), item.getObjectKey()) && Objects.equals(previous.url(), item.getUrl()))).toList();
        var auditSnapshot = posts.selectById(id);
        boolean pending = auditSnapshot != null && !Integer.valueOf(AuditStatus.PASS.getCode()).equals(auditSnapshot.getAuditStatus());
        boolean checked = pending || fields.contains("content") || fields.contains("title") || !changedMedia.isEmpty();
        if (checked) {
            safety.check(actor, id, (pending || fields.contains("content") || fields.contains("title")) ? Objects.toString(title, "") + "\n" + content : null, pending ? candidate : changedMedia);
        }
        return new TransactionTemplate(transactions).execute(status -> {
            users.lockCommunityWriter(actor);
            var post = posts.lockPost(id);
            authorize(post);
            requireEditable(post);
            PostProblems.match(ifMatch, version(post).etag());
            users.assertContentCreationAllowed(actor);
            organization.validateReferences(fields.contains("categoryId") ? post.getPostType() : "NORMAL", fields.contains("categoryId") ? category : null, fields.contains("topicIds") ? topics : null);
            if("MENTOR_QA".equals(post.getPostType())) {
                boolean changed=!Objects.equals(title,old.title()) || !Objects.equals(content,old.content()) || !candidate.stream().map(m->new PostAttachmentInputBO(m.getType(),m.getObjectKey(),m.getUrl(),m.getOriginalName(),m.getSortOrder())).toList().equals(old.attachmentItems());
                mentorQuestions.getObject().edit(id,changed);
            }
            if (fields.contains("attachmentItems")) {
                media.replaceAttachments(MediaAttachmentSaveBO.builder().targetType(TargetType.POST).targetId(id).attachments(candidate).build());
            }
            if (fields.contains("topicIds")) {
                organization.replaceTopics(id, topics);
            }
            int count = posts.applyEdit(new PostEffectiveEditBO(id, title, content, category, post.getRevision(), checked));
            if (count != 1) {
                throw ContractProblemException.preconditionFailed();
            }
            post.setTitle(title);
            post.setContent(content);
            post.setCategoryId(category);
            post.setRevision(post.getRevision() + 1);
            return version(post);
        });
    }

    PostVersionedBO<PostEditableBO> version(Post post) {
        var projection = organization.project(List.of(post.getId()), List.of());
        var ownedMedia = mediaQueries.listAttachments(TargetType.POST, post.getId());
        var attachments = ownedMedia.stream().map(m -> new PostAttachmentInputBO(m.getType(), m.getObjectKey(), m.getUrl(), m.getOriginalName(), m.getSortOrder())).toList();
        var value = new PostEditableBO(post.getId(), post.getPostType(), post.getTitle(), post.getContent(), attachments, post.getCategoryId(), projection.topicIds().getOrDefault(post.getId(), List.of()));
        return new PostVersionedBO<>(value, tags.tag(new EditableTagFacts(value, ownedMedia), post.getRevision()));
    }

    private record EditableTagFacts(PostEditableBO representation,
                                    List<cn.jualn.miniapp.module.media.bo.MediaAttachmentBO> media) {
    }

    private void requireEditable(Post post) {
        if (Boolean.TRUE.equals(post.getAdminHidden())) throw ContractProblemException.conflict("post-moderation-conflict","Managed hidden content cannot be edited");

    }

    private void authorize(Post post) {
        long actor = requireUser();
        if (post == null) {
            throw new BusinessException(ResultCode.POST_NOT_FOUND);
        }
        if (!Objects.equals(post.getUserId(), actor)) {
            throw new BusinessException(ResultCode.POST_NOT_FOUND);
        }
        if (!Set.of("NORMAL", "MENTOR_QA").contains(post.getPostType())) {
            throw PostProblems.invalid("postType", "INVALID_VALUE", "Unsupported stored type");
        }
    }

    private long requireUser() {
        Long user = UserContext.getUserId();
        if (user == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        return user;
    }

    private void validate(PostEditBO c) {
        if (c == null || c.fields().isEmpty()) {
            throw PostProblems.invalid("body", "REQUIRED", "Provide at least one editable field");
        }
        if (c.fields().contains("title") && c.title() != null && c.title().length() > 128) {
            throw PostProblems.invalid("title", "INVALID_VALUE", "Title too long");
        }
        if (c.fields().contains("content") && (c.content() == null || c.content().length() > 5000)) {
            throw PostProblems.invalid("content", "INVALID_VALUE", "Invalid content");
        }
        if (c.fields().contains("topicIds") && c.topicIds() == null) {
            throw PostProblems.invalid("topicIds", "INVALID_VALUE", "topicIds must be an array");
        }
        if (c.fields().contains("attachmentItems") && (c.attachmentItems() == null || c.attachmentItems().size() > 9)) {
            throw PostProblems.invalid("attachmentItems", "INVALID_VALUE", "Use at most nine attachments");
        }
        if (c.attachmentItems() != null && c.attachmentItems().stream().anyMatch(m -> m == null || m.getType() == null || !Set.of(MediaType.URL, MediaType.IMAGE, MediaType.PDF, MediaType.WORD).contains(m.getType()))) {
            throw PostProblems.invalid("attachmentItems", "INVALID_VALUE", "Unsupported Post attachment type");
        }
    }
}
