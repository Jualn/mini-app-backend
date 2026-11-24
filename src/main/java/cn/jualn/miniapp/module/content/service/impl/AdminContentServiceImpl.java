package cn.jualn.miniapp.module.content.service.impl;

import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.module.user.enums.UserStatus;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.module.comment.bo.AdminCommentActionBO;
import cn.jualn.miniapp.module.comment.enums.CommentStatus;
import cn.jualn.miniapp.module.comment.service.CommentGovernanceService;
import cn.jualn.miniapp.module.content.bo.*;
import cn.jualn.miniapp.module.content.enums.AdminContentAction;
import cn.jualn.miniapp.module.content.enums.AdminContentType;
import cn.jualn.miniapp.module.content.mapper.AdminContentDetailRow;
import cn.jualn.miniapp.module.content.mapper.AdminContentListRow;
import cn.jualn.miniapp.module.content.mapper.AdminContentMapper;
import cn.jualn.miniapp.module.content.mapper.AdminContentSummaryRow;
import cn.jualn.miniapp.module.content.service.AdminContentService;
import cn.jualn.miniapp.module.media.bo.MediaAttachmentBO;
import cn.jualn.miniapp.module.media.service.MediaQueries;
import cn.jualn.miniapp.module.post.bo.AdminPostActionBO;
import cn.jualn.miniapp.module.post.service.PostGovernanceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AdminContentServiceImpl implements AdminContentService {
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.beans.factory.ObjectProvider<cn.jualn.miniapp.module.media.service.MediaDeliveryUrls> deliveryUrls;
    private String previewUrl(String url){return url!=null && url.contains(cn.jualn.miniapp.module.media.service.MediaDeliveryUrls.PATH)?deliveryUrls.getObject().adminVariant(url):url;}

    @Override public void lockReportTarget(AdminContentType type,long id) {if(type==AdminContentType.POST)postService.lockForReport(id);else commentService.lockForReport(id); }
    private final cn.jualn.miniapp.module.authorization.service.CurrentAuthorization authorization;
    @org.springframework.beans.factory.annotation.Autowired private cn.jualn.miniapp.module.post.service.GrowthRecordAccess growthAccess;
    @org.springframework.beans.factory.annotation.Autowired private cn.jualn.miniapp.module.post.service.GrowthRecordService growthRecords;
    @org.springframework.beans.factory.annotation.Autowired private cn.jualn.miniapp.module.content.service.GrowthManagementTargets growthTargets;
    @Override public void authorizeDisposal(AdminContentType type,Long id,long actor) {
        if(growthTargets.require(type.getCode(),id,actor,"POST_MODERATE","content:read",type==AdminContentType.POST?"post:dispose":"comment:dispose"))return;
        authorization.require(actor,"content:manage",type==AdminContentType.POST?"POST":commentService.managementNamespace(id),
                type==AdminContentType.POST?"post:dispose":"comment:dispose");
    }


    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 50;

    @org.springframework.beans.factory.annotation.Autowired private org.springframework.beans.factory.ObjectProvider<cn.jualn.miniapp.module.post.service.MentorQuestions> mentorQuestions;
    private final AdminContentMapper adminContentMapper;
    private final cn.jualn.miniapp.module.post.service.PostManagementQueries community;
    private final PostGovernanceService postService;
    private final CommentGovernanceService commentService;
    private final MediaQueries mediaService;
    private final cn.jualn.miniapp.module.post.service.PostOrganizationService organization;
    private final cn.jualn.miniapp.module.content.service.AdminQaContextReader qa;


    @Override
    public AdminContentPageBO pageContents(AdminContentQueryBO query) {
        long actor=cn.jualn.miniapp.common.security.AdminStpUtil.STP_LOGIC.getLoginIdAsLong();
        var decision=authorization.current(actor);
        boolean growth=query!=null && cn.jualn.miniapp.module.post.service.GrowthRecordAccess.growthFilter(query.getGrowthType(),query.getGrowthOrganizationId());
        var growthOrganizations=growth?growthAccess.organizations(actor,"POST_MODERATE","content:read"):List.<String>of();
        if(growth && (growthOrganizations.isEmpty() || query.getGrowthOrganizationId()!=null && !growthOrganizations.contains(query.getGrowthOrganizationId())))throw cn.jualn.miniapp.module.post.GrowthProblems.missing();
        if(growth && query.getGrowthType()!=null && !java.util.Set.of("ALL","GROWTH","POLITICAL_BIRTHDAY").contains(query.getGrowthType()))throw cn.jualn.miniapp.module.post.PostProblems.invalid("growthType","INVALID_VALUE","Invalid growth type");
        java.util.List<String> namespaces=null;
        if(growth) namespaces=List.of("POST");
        else if(decision.source()==cn.jualn.miniapp.module.authorization.model.RbacModels.Source.LEGACY) authorization.require(actor,"content:read","POST","content:read");
        else {
            namespaces=java.util.stream.Stream.concat(decision.grants().stream().map(g->g.namespace()),decision.ownerCapabilities().stream().map(c->c.namespace()))
                    .distinct().filter(n->!"ARTICLE".equals(n)&&decision.allows("content:read",n)).toList();
            if(namespaces.isEmpty()) throw new BusinessException(ResultCode.FORBIDDEN);
            if(query!=null && "post".equals(query.getType())) authorization.require(actor,"content:read","POST","content:read");
        }

        AdminContentQueryBO actual = query == null ? AdminContentQueryBO.builder().build() : query;
        int pageSize = normalizePageSize(actual.getPageSize());
        String sort = StringUtils.hasText(actual.getSort()) ? actual.getSort() : "latest";
        boolean filtered=actual.getPostType()!=null || actual.getCategoryId()!=null || actual.getTopicId()!=null;
        if(filtered && !"post".equals(actual.getType())) throw cn.jualn.miniapp.common.exception.ContractProblemException.validation(new cn.jualn.miniapp.common.exception.ContractProblemException.Violation("query","/type","INVALID_VALUE","Post filters require type=post"));
        if(actual.getPostType()!=null && !java.util.Set.of("NORMAL","MENTOR_QA").contains(actual.getPostType())) throw cn.jualn.miniapp.common.exception.ContractProblemException.validation(new cn.jualn.miniapp.common.exception.ContractProblemException.Violation("query","/postType","INVALID_VALUE","Invalid post type"));
        try {organization.validateFilter(actual.getCategoryId(),actual.getTopicId());}
        catch(cn.jualn.miniapp.common.exception.ContractProblemException failure) {
            if(!java.util.Set.of("/problems/post-category-invalid","/problems/post-topic-invalid").contains(failure.getType()))throw failure;
            throw cn.jualn.miniapp.common.exception.ContractProblemException.validation(new cn.jualn.miniapp.common.exception.ContractProblemException.Violation(
                "query",failure.getType().contains("category")?"/categoryId":"/topicId","INVALID_VALUE","Unknown Post dictionary ID"));
        }
        String binding=filterBinding(actual,sort,actor);
        Cursor cursor = decodeCursor(actual.getCursor(), binding);
        String keyword = trimToNull(actual.getKeyword());

        List<AdminContentListRow> rows = growth ? adminContentMapper.selectGrowthPage(keyword,parseId(keyword),actual.getType(),actual.getStatus(),actual.getFeature(),sort,cursor==null?null:cursor.updatedAt(),cursor==null?null:cursor.metric(),cursor==null?null:cursor.type(),cursor==null?null:cursor.id(),actual.getPostType(),actual.getCategoryId(),actual.getTopicId(),actual.getGrowthType(),actual.getGrowthOrganizationId(),growthOrganizations,pageSize+1,namespaces) : adminContentMapper.selectPage(
                keyword,
                parseId(keyword),
                actual.getType(),
                actual.getStatus(),
                actual.getFeature(),
                sort,
                cursor == null ? null : cursor.updatedAt(),
                cursor == null ? null : cursor.metric(),
                cursor == null ? null : cursor.type(),
                cursor == null ? null : cursor.id(),
                actual.getPostType(),actual.getCategoryId(),actual.getTopicId(),pageSize + 1,namespaces);

        boolean hasMore = rows.size() > pageSize;
        if (hasMore) {
            rows = rows.subList(0, pageSize);
        }
        if(!growth && authorization.current(actor).source()==cn.jualn.miniapp.module.authorization.model.RbacModels.Source.ASSIGNMENT) {
            for(var row:rows) if("comment".equals(row.getType()))
                authorization.require(actor,"content:read",commentService.managementNamespace(row.getId()),"content:read");
        }
        List<AdminContentListBO> items = rows.stream().map(this::toListBO).toList();
        var locations=growthTargets.locations(items.stream().map(i->new cn.jualn.miniapp.module.content.bo.ManagementTarget.Reference(i.getType(),i.getId())).toList());
        for(var item:items)item.setManagementTarget(locations.get(item.getType()+":"+item.getId()));
        if(growth)for(var item:items){
            item.setGrowthRecord(growthTargets.link(item.getType(),item.getId()));
            var actions=new ArrayList<String>();actions.add("view");
            for(var action:item.getAvailableActions()) {
                String permission=java.util.Set.of("pin","unpin","feature","unfeature").contains(action)?"post:curate":"post".equals(item.getType())?"post:dispose":"comment:dispose";
                if(growthAccess.allowed(decision,item.getGrowthRecord(),"POST_MODERATE","content:read",permission))actions.add(action);
            }
            item.setAvailableActions(List.copyOf(actions));
        }
        var postRows=rows.stream().filter(row->"post".equals(row.getType())).toList();
        if(!postRows.isEmpty()) {
            var projection=organization.project(postRows.stream().map(AdminContentListRow::getId).toList(),postRows.stream().map(AdminContentListRow::getCategoryId).filter(java.util.Objects::nonNull).distinct().toList());
            var byId=postRows.stream().collect(java.util.stream.Collectors.toMap(AdminContentListRow::getId,java.util.function.Function.identity()));
            for(var item:items) if("post".equals(item.getType())) {var row=byId.get(item.getId());
                item.setPostOrganization(new AdminPostProjection.Organization(row.getPostType(),projection.categories().get(row.getCategoryId()),projection.topics().getOrDefault(row.getId(),List.of())));
            }
        }
        AdminContentSummaryRow summary = growth ? adminContentMapper.selectGrowthSummary(actual.getType(),actual.getGrowthType(),actual.getGrowthOrganizationId(),growthOrganizations) : namespaces==null?adminContentMapper.selectSummary():adminContentMapper.selectScopedSummary(actual.getType(),namespaces);
        return AdminContentPageBO.builder()
                .items(items)
                .summary(AdminContentPageBO.Summary.builder()
                        .published(summary == null ? 0L : summary.getPublished())
                        .pending(summary == null ? 0L : summary.getPending())
                        .reported(summary == null ? 0L : summary.getReported())
                        .removedToday(summary == null ? 0L : summary.getRemovedToday())
                        .build())
                .hasMore(hasMore)
                .nextCursor(rows.isEmpty() ? null : encodeCursor(rows.get(rows.size() - 1), sort, binding))
                .pageSize(pageSize)
                .build();
    }

    @Override
    public AdminContentDetailBO getContentDetail(AdminContentType type, Long contentId) {
        if(growthTargets.require(type.getCode(),contentId,cn.jualn.miniapp.common.security.AdminStpUtil.STP_LOGIC.getLoginIdAsLong(),"POST_MODERATE","content:read"))return detail(type,contentId);
        authorization.require(cn.jualn.miniapp.common.security.AdminStpUtil.STP_LOGIC.getLoginIdAsLong(),"content:read",type==AdminContentType.POST?"POST":commentService.managementNamespace(contentId),"content:read");
        return detail(type,contentId);
    }
    @Override public AdminContentDetailBO getContentDetailForReport(AdminContentType type,Long contentId) {
        if(growthTargets.require(type.getCode(),contentId,cn.jualn.miniapp.common.security.AdminStpUtil.STP_LOGIC.getLoginIdAsLong(),"REPORT_HANDLE","report:read"))return detail(type,contentId,true);
        authorization.require(cn.jualn.miniapp.common.security.AdminStpUtil.STP_LOGIC.getLoginIdAsLong(),"report:read","REPORT","report:read");
        return detail(type,contentId,true);
    }
    private AdminContentDetailBO detail(AdminContentType type,Long contentId) {
        return detail(type,contentId,false);
    }
    private AdminContentDetailBO detail(AdminContentType type,Long contentId,boolean reportEvidence) {
        if(type==AdminContentType.COMMENT && authorization.current(cn.jualn.miniapp.common.security.AdminStpUtil.STP_LOGIC.getLoginIdAsLong()).source()
                ==cn.jualn.miniapp.module.authorization.model.RbacModels.Source.ASSIGNMENT && "ARTICLE".equals(commentService.managementNamespace(contentId)))
            throw new BusinessException(ResultCode.FORBIDDEN);
        if (type == null) {
            throw new BusinessException(ResultCode.INVALID_TARGET_TYPE, "仅支持帖子和评论");
        }
        if (contentId == null) {
            throw new BusinessException(ResultCode.TARGET_NOT_FOUND);
        }
        AdminContentDetailRow row = type == AdminContentType.POST
                ? adminContentMapper.selectPostDetail(contentId)
                : adminContentMapper.selectCommentDetail(contentId);
        if (row == null) {
            throw new BusinessException(ResultCode.TARGET_NOT_FOUND, "内容不存在");
        }

        AdminContentDetailBO detail = toDetailBO(row);
        detail.setManagementTarget(growthTargets.location(type.getCode(),contentId));
        var growthLink=growthTargets.link(type.getCode(),contentId);detail.setGrowthRecord(growthLink);
        if(type==AdminContentType.POST) {
            var projection=organization.project(List.of(row.getId()),row.getCategoryId()==null?List.of():List.of(row.getCategoryId()));
            detail.setPostOrganization(new AdminPostProjection.Organization(row.getPostType(),projection.categories().get(row.getCategoryId()),projection.topics().getOrDefault(row.getId(),List.of())));
        }
        if(growthLink==null && type==AdminContentType.POST && java.util.Set.of("NORMAL","MENTOR_QA").contains(row.getPostType())) {
            var state=reportEvidence?community.reportEvidence(contentId):community.get(contentId,cn.jualn.miniapp.common.security.AdminStpUtil.STP_LOGIC.getLoginIdAsLong());
            detail.setCommunityLifecycle(state.lifecycle());detail.setManagementHistory(state.history());detail.setEditableEtag(state.editableEtag());
            var actions=new ArrayList<>(detail.getAvailableActions());if(state.restorable())actions.add("restore");if(state.curatable())actions.add("change-category");detail.setAvailableActions(actions);
        }
        if(type==AdminContentType.COMMENT && !reportEvidence)detail.setManagementHistory(growthLink==null || growthAccess.allowedAdmin(growthLink.postId(),cn.jualn.miniapp.common.security.AdminStpUtil.STP_LOGIC.getLoginIdAsLong(),"POST_MODERATE","content:read")?commentService.managementHistory(contentId):List.of());
        detail.setAttachments(buildAttachments(type, row));
        detail.setContext(buildContext(type, row));
        detail.setQaContext(reportEvidence?qa.reportEvidence(type.getCode(),contentId):qa.management(type.getCode(),contentId));
        // 当前表结构没有统一的管理操作审计记录；不伪造历史。
        detail.setHistory(List.of());
        if(growthLink!=null){long operator=cn.jualn.miniapp.common.security.AdminStpUtil.STP_LOGIC.getLoginIdAsLong();String duty=growthAccess.allowedAdmin(growthLink.postId(),operator,"POST_MODERATE","content:read")?"POST_MODERATE":"REPORT_HANDLE";
            var state=growthRecords.management(growthLink.postId(),operator,duty,duty.equals("POST_MODERATE")?"content:read":"report:read");
            detail.setAttachments(List.of());detail.setEditableEtag(state.editableEtag());if(type==AdminContentType.POST)detail.setManagementHistory(state.managementHistory());
            detail.setGrowthLifecycle(state.growthLifecycle());detail.setGrowthMedia(state.growthMedia().stream().map(f->new cn.jualn.miniapp.module.media.bo.GrowthMediaBO.File(f.mediaId(),f.kind(),f.originalName(),f.contentType(),f.sizeBytes(),f.safety(),deliveryUrls.getObject().adminVariant(f.contentUrl()),f.unboundExpiresAt())).toList());detail.setCommunityLifecycle(null);
            var actions=new ArrayList<String>();actions.add("view");
            String dispose=type==AdminContentType.POST?"post:dispose":"comment:dispose";
            if(!state.growthLifecycle().authorDeleted() && growthAccess.allowedAdmin(growthLink.postId(),operator,"POST_MODERATE","content:read",dispose)){
                if(type==AdminContentType.COMMENT){if(commentService.canRestore(contentId))actions.add("restore");else if("published".equals(row.getStatus()))actions.add("take-down");}
                else if(!state.growthLifecycle().adminHidden())actions.add("take-down");
                else if("PASSED".equals(state.growthLifecycle().safety()) && state.managementHistory().stream().anyMatch(h->"TAKE_DOWN".equals(h.action())))actions.add("restore");
            }
            if(type==AdminContentType.POST && !state.growthLifecycle().authorDeleted() && !state.growthLifecycle().adminHidden() && growthAccess.allowedAdmin(growthLink.postId(),operator,"POST_MODERATE","content:read","post:curate"))actions.add("change-category");
            detail.setAvailableActions(actions);
        }
        return detail;
    }

    @Override
    public void executeAction(AdminContentActionBO command) {
        if (command == null || command.getOperatorId() == null || command.getType() == null
                || command.getContentId() == null || command.getAction() == null) {
            throw new BusinessException(ResultCode.INVALID_OPERATION, "内容管理操作参数不完整");
        }
        String reason = trimToNull(command.getReason());
        if ((command.getAction() == AdminContentAction.TAKE_DOWN
                || command.getAction() == AdminContentAction.RESTORE) && reason == null) {
            throw new BusinessException(ResultCode.INVALID_OPERATION, "下架或恢复必须填写原因");
        }

        if (command.getType() == AdminContentType.POST) {
            executePostAction(command, reason);
            return;
        }
        executeCommentAction(command, reason);
    }

    @Override
    public void executeReportTakeDown(AdminContentActionBO command) {
        if(command==null || command.getAction()!=AdminContentAction.TAKE_DOWN) throw new BusinessException(ResultCode.INVALID_OPERATION);
        if(command.getType()==AdminContentType.POST) postService.takeDownReportedPost(AdminPostActionBO.builder()
                .operatorId(command.getOperatorId()).postId(command.getContentId()).reason(command.getReason()).build());
        else commentService.takeDownReportedComment(AdminCommentActionBO.builder()
                .operatorId(command.getOperatorId()).commentId(command.getContentId()).reason(command.getReason()).build());
    }

    private void executePostAction(AdminContentActionBO command, String reason) {
        AdminPostActionBO action = AdminPostActionBO.builder()
                .operatorId(command.getOperatorId())
                .postId(command.getContentId())
                .reason(reason)
                .build();
        switch (command.getAction()) {
            case PIN -> postService.pinPost(action);
            case UNPIN -> postService.unpinPost(action);
            case FEATURE -> postService.featurePost(action);
            case UNFEATURE -> postService.unfeaturePost(action);
            case TAKE_DOWN -> postService.takeDownPost(action);
            case RESTORE -> postService.restorePost(action);
        }
    }

    private void executeCommentAction(AdminContentActionBO command, String reason) {
        if (command.getAction() != AdminContentAction.TAKE_DOWN
                && command.getAction() != AdminContentAction.RESTORE) {
            throw new BusinessException(ResultCode.INVALID_OPERATION, "评论不支持置顶或推荐");
        }
        AdminCommentActionBO action = AdminCommentActionBO.builder()
                .operatorId(command.getOperatorId())
                .commentId(command.getContentId())
                .reason(reason)
                .build();
        if (command.getAction() == AdminContentAction.TAKE_DOWN) {
            commentService.takeDownComment(action);
        } else {
            commentService.restoreComment(action);
        }
    }

    private AdminContentListBO toListBO(AdminContentListRow row) {
        AdminContentListBO item = new AdminContentListBO();
        copyListFields(row, item);
        item.setAvailableActions(availableActions(row));
        return item;
    }

    private AdminContentDetailBO toDetailBO(AdminContentDetailRow row) {
        AdminContentDetailBO detail = new AdminContentDetailBO();
        copyListFields(row, detail);
        detail.setAvailableActions(availableActions(row));
        detail.setContent(row.getContent());
        detail.setRejectReason(row.getRejectReason());
        detail.setImageUrl(row.getImageUrl());
        detail.setTargetType(row.getTargetType());
        detail.setTargetId(row.getTargetId());
        detail.setParentId(row.getParentId());

        AdminContentDetailBO.Author author = new AdminContentDetailBO.Author();
        author.setAvatarText(firstCharacter(row.getAuthorName()));
        author.setStatusLabel(UserStatus.fromCode(row.getAuthorStatus()).getDesc());
        author.setJoinedAt(row.getAuthorCreatedAt());
        author.setPublishedCount(valueOrZero(row.getAuthorPublishedCount()));
        author.setViolationCount(valueOrZero(row.getAuthorViolationCount()));
        detail.setAuthor(author);
        return detail;
    }

    private void copyListFields(AdminContentListRow row, AdminContentListBO item) {
        item.setId(row.getId());
        item.setType(row.getType());
        item.setTitle(row.getTitle());
        item.setContentPreview(row.getContentPreview());
        item.setAuthorName(row.getAuthorName());
        item.setAuthorId(row.getAuthorId());
        item.setStatus(row.getStatus());
        item.setAuditStatus(row.getAuditStatus());
        item.setPinned(row.getPinned());
        item.setFeatured(row.getFeatured());
        item.setReportCount(valueOrZero(row.getReportCount()));
        item.setViewCount(row.getViewCount());
        item.setLikeCount(valueOrZero(row.getLikeCount()));
        item.setCommentCount(valueOrZero(row.getCommentCount()));
        item.setShareCount(row.getShareCount());
        item.setPublishedAt(row.getPublishedAt());
        item.setUpdatedAt(row.getUpdatedAt());
        item.setRawStatus(row.getRawStatus());
        if("post".equals(row.getType()) && "MENTOR_QA".equals(row.getPostType()))item.setQaSummary(mentorQuestions.getObject().summary(row.getId()));

    }

    private List<String> availableActions(AdminContentListRow row) {
        List<String> actions = new ArrayList<>();
        if ("post".equals(row.getType()) && "published".equals(row.getStatus())) {
            actions.add(Boolean.TRUE.equals(row.getPinned()) ? "unpin" : "pin");
            actions.add(Boolean.TRUE.equals(row.getFeatured()) ? "unfeature" : "feature");
            actions.add("take-down");
        } else if ("comment".equals(row.getType()) && "published".equals(row.getStatus())) {
            actions.add("take-down");
        } else if ("comment".equals(row.getType()) && "removed".equals(row.getStatus())
                && row.getRawStatus() != null
                && row.getRawStatus() == CommentStatus.ADMIN_DELETED.getCode()
                && "approved".equals(row.getAuditStatus()) && commentService.canRestore(row.getId())) {
            actions.add("restore");
        }
        return actions;
    }

    private List<AdminContentDetailBO.Attachment> buildAttachments(
            AdminContentType type,
            AdminContentDetailRow row) {
        if (type == AdminContentType.POST) {
            return mediaService.listAttachments(TargetType.POST, row.getId()).stream()
                    .map(this::toAttachment)
                    .toList();
        }
        if (!StringUtils.hasText(row.getImageUrl())) {
            return List.of();
        }
        AdminContentDetailBO.Attachment attachment = new AdminContentDetailBO.Attachment();
        attachment.setId("comment-" + row.getId() + "-image");
        attachment.setType("image");
        attachment.setName("评论图片");
        attachment.setUrl(previewUrl(row.getImageUrl()));
        return List.of(attachment);
    }

    private AdminContentDetailBO.Attachment toAttachment(MediaAttachmentBO media) {
        AdminContentDetailBO.Attachment attachment = new AdminContentDetailBO.Attachment();
        attachment.setId(media.getId().toString());
        attachment.setType(media.getType() == null
                ? "unknown"
                : media.getType().name().toLowerCase());
        attachment.setName(StringUtils.hasText(media.getOriginalName())
                ? media.getOriginalName()
                : "附件 " + media.getId());
        attachment.setUrl(previewUrl(media.getUrl()));
        return attachment;
    }

    private List<AdminContentDetailBO.ContextItem> buildContext(
            AdminContentType type,
            AdminContentDetailRow row) {
        List<AdminContentDetailBO.ContextItem> context = new ArrayList<>();
        if (type == AdminContentType.POST) {
            context.add(contextItem("内容来源", "用户发布"));
            context.add(contextItem("关联评论", valueOrZero(row.getCommentCount()) + " 条"));
        } else {
            TargetType targetType = TargetType.fromCode(row.getTargetType());
            context.add(contextItem("所属目标", (targetType == null ? "未知类型" : targetType.getDesc())
                    + " #" + row.getTargetId()));
            context.add(contextItem("评论层级", row.getParentId() == null ? "一级评论" : "回复"));
            context.add(contextItem("子回复", valueOrZero(row.getCommentCount()) + " 条"));
        }
        context.add(contextItem("待处理举报", valueOrZero(row.getReportCount()) + " 条"));
        return context;
    }

    private AdminContentDetailBO.ContextItem contextItem(String label, String value) {
        AdminContentDetailBO.ContextItem item = new AdminContentDetailBO.ContextItem();
        item.setLabel(label);
        item.setValue(value);
        return item;
    }

    private String filterBinding(AdminContentQueryBO q,String sort,long actor) {
        String value=java.util.Arrays.asList(actor,trimToNull(q.getKeyword()),q.getType(),q.getStatus(),q.getFeature(),sort,q.getPostType(),q.getCategoryId(),q.getTopicId(),q.getGrowthType(),q.getGrowthOrganizationId()).toString();
        try {return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException failure) {throw new IllegalStateException(failure);}
    }
    private int normalizePageSize(Integer pageSize) {
        return pageSize == null ? DEFAULT_PAGE_SIZE : Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
    }

    private Long parseId(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String encodeCursor(AdminContentListRow row, String sort, String binding) {
        long metric = "most-viewed".equals(sort)
                ? valueOrZero(row.getViewCount())
                : "most-reported".equals(sort) ? valueOrZero(row.getReportCount()) : 0L;
        String value = String.join("|", binding, row.getUpdatedAt().toString(), Long.toString(metric),
                row.getType(), row.getId().toString());
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private Cursor decodeCursor(String encoded, String expectedSort) {
        if (!StringUtils.hasText(encoded)) {
            return null;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            String[] parts = decoded.split("\\|", -1);
            if (parts.length != 5 || !expectedSort.equals(parts[0])) {
                throw new IllegalArgumentException("cursor mismatch");
            }
            return new Cursor(LocalDateTime.parse(parts[1]), Long.parseLong(parts[2]),
                    parts[3], Long.parseLong(parts[4]));
        } catch (IllegalArgumentException | DateTimeParseException e) {
            throw new cn.jualn.miniapp.common.exception.ContractProblemException(org.springframework.http.HttpStatus.BAD_REQUEST,"/problems/invalid-cursor","Invalid cursor");
        }
    }

    private String firstCharacter(String value) {
        if (!StringUtils.hasText(value)) {
            return "用";
        }
        return new String(Character.toChars(value.codePointAt(0)));
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private long valueOrZero(Long value) {
        return value == null ? 0L : value;
    }

    private record Cursor(LocalDateTime updatedAt, Long metric, String type, Long id) {
    }
}
