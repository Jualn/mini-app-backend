package cn.jualn.miniapp.module.content.service.impl;

import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.enums.UserStatus;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.module.comment.bo.AdminCommentActionBO;
import cn.jualn.miniapp.module.comment.enums.CommentStatus;
import cn.jualn.miniapp.module.comment.service.CommentService;
import cn.jualn.miniapp.module.content.bo.*;
import cn.jualn.miniapp.module.content.enums.AdminContentAction;
import cn.jualn.miniapp.module.content.enums.AdminContentType;
import cn.jualn.miniapp.module.content.mapper.AdminContentDetailRow;
import cn.jualn.miniapp.module.content.mapper.AdminContentListRow;
import cn.jualn.miniapp.module.content.mapper.AdminContentMapper;
import cn.jualn.miniapp.module.content.mapper.AdminContentSummaryRow;
import cn.jualn.miniapp.module.content.service.AdminContentService;
import cn.jualn.miniapp.module.media.bo.MediaAttachmentBO;
import cn.jualn.miniapp.module.media.service.MediaService;
import cn.jualn.miniapp.module.post.bo.AdminPostActionBO;
import cn.jualn.miniapp.module.post.service.PostService;
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

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 50;

    private final AdminContentMapper adminContentMapper;
    private final PostService postService;
    private final CommentService commentService;
    private final MediaService mediaService;

    @Override
    public AdminContentPageBO pageContents(AdminContentQueryBO query) {
        AdminContentQueryBO actual = query == null ? AdminContentQueryBO.builder().build() : query;
        int pageSize = normalizePageSize(actual.getPageSize());
        String sort = StringUtils.hasText(actual.getSort()) ? actual.getSort() : "latest";
        Cursor cursor = decodeCursor(actual.getCursor(), sort);
        String keyword = trimToNull(actual.getKeyword());

        List<AdminContentListRow> rows = adminContentMapper.selectPage(
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
                pageSize + 1);

        boolean hasMore = rows.size() > pageSize;
        if (hasMore) {
            rows = rows.subList(0, pageSize);
        }
        List<AdminContentListBO> items = rows.stream().map(this::toListBO).toList();
        AdminContentSummaryRow summary = adminContentMapper.selectSummary();
        return AdminContentPageBO.builder()
                .items(items)
                .summary(AdminContentPageBO.Summary.builder()
                        .published(summary == null ? 0L : summary.getPublished())
                        .pending(summary == null ? 0L : summary.getPending())
                        .reported(summary == null ? 0L : summary.getReported())
                        .removedToday(summary == null ? 0L : summary.getRemovedToday())
                        .build())
                .hasMore(hasMore)
                .nextCursor(rows.isEmpty() ? null : encodeCursor(rows.get(rows.size() - 1), sort))
                .pageSize(pageSize)
                .build();
    }

    @Override
    public AdminContentDetailBO getContentDetail(AdminContentType type, Long contentId) {
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
        detail.setAttachments(buildAttachments(type, row));
        detail.setContext(buildContext(type, row));
        // 当前表结构没有统一的管理操作审计记录；不伪造历史。
        detail.setHistory(List.of());
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
            case RESTORE -> throw new BusinessException(ResultCode.INVALID_OPERATION,
                    "帖子暂不能安全恢复：当前数据无法区分用户删除和管理员下架");
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
                && "approved".equals(row.getAuditStatus())) {
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
        attachment.setUrl(row.getImageUrl());
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
        attachment.setUrl(media.getUrl());
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

    private String encodeCursor(AdminContentListRow row, String sort) {
        long metric = "most-viewed".equals(sort)
                ? valueOrZero(row.getViewCount())
                : "most-reported".equals(sort) ? valueOrZero(row.getReportCount()) : 0L;
        String value = String.join("|", sort, row.getUpdatedAt().toString(), Long.toString(metric),
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
            throw new BusinessException(ResultCode.INVALID_OPERATION, "分页游标无效或与排序方式不匹配");
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
