package cn.jualn.miniapp.module.content.converter;

import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.module.content.bo.*;
import cn.jualn.miniapp.module.content.dto.admin.AdminContentActionRequest;
import cn.jualn.miniapp.module.content.dto.admin.AdminContentPageQuery;
import cn.jualn.miniapp.module.content.enums.AdminContentAction;
import cn.jualn.miniapp.module.content.enums.AdminContentType;
import cn.jualn.miniapp.module.content.vo.admin.AdminContentDetailVO;
import cn.jualn.miniapp.module.content.vo.admin.AdminContentListVO;
import cn.jualn.miniapp.module.content.vo.admin.AdminContentPageVO;
import org.springframework.stereotype.Component;

@Component
public class AdminContentConverter {

    public AdminContentQueryBO toQueryBO(AdminContentPageQuery query) {
        return AdminContentQueryBO.builder()
                .keyword(trimToNull(query.getKeyword()))
                .type(query.getType())
                .status(query.getStatus())
                .feature(query.getFeature())
                .sort(query.getSort())
                .cursor(query.getCursor())
                .pageSize(query.getPageSize())
                .build();
    }

    public AdminContentType toType(String value) {
        AdminContentType type = AdminContentType.fromCode(value);
        if (type == null) {
            throw new BusinessException(ResultCode.INVALID_TARGET_TYPE, "仅支持 post 或 comment");
        }
        return type;
    }

    public AdminContentActionBO toActionBO(
            Long operatorId,
            String type,
            Long contentId,
            String action,
            AdminContentActionRequest request) {
        AdminContentAction parsedAction = AdminContentAction.fromCode(action);
        if (parsedAction == null) {
            throw new BusinessException(ResultCode.INVALID_OPERATION, "不支持的内容管理动作");
        }
        return AdminContentActionBO.builder()
                .operatorId(operatorId)
                .type(toType(type))
                .contentId(contentId)
                .action(parsedAction)
                .reason(request == null ? null : trimToNull(request.getReason()))
                .build();
    }

    public AdminContentPageVO toPageVO(AdminContentPageBO page) {
        return AdminContentPageVO.builder()
                .items(page.getItems().stream().map(this::toListVO).toList())
                .summary(AdminContentPageVO.Summary.builder()
                        .published(page.getSummary().getPublished())
                        .pending(page.getSummary().getPending())
                        .reported(page.getSummary().getReported())
                        .removedToday(page.getSummary().getRemovedToday())
                        .build())
                .hasMore(page.getHasMore())
                .nextCursor(page.getNextCursor())
                .pageSize(page.getPageSize())
                .build();
    }

    public AdminContentDetailVO toDetailVO(AdminContentDetailBO detail) {
        AdminContentDetailVO vo = new AdminContentDetailVO();
        copyListFields(detail, vo);
        vo.setContent(detail.getContent());
        vo.setRejectReason(detail.getRejectReason());

        AdminContentDetailVO.Author author = new AdminContentDetailVO.Author();
        author.setAvatarText(detail.getAuthor().getAvatarText());
        author.setStatusLabel(detail.getAuthor().getStatusLabel());
        author.setJoinedAt(detail.getAuthor().getJoinedAt());
        author.setPublishedCount(detail.getAuthor().getPublishedCount());
        author.setViolationCount(detail.getAuthor().getViolationCount());
        vo.setAuthor(author);

        vo.setAttachments(detail.getAttachments().stream().map(item -> {
            AdminContentDetailVO.Attachment target = new AdminContentDetailVO.Attachment();
            target.setId(item.getId());
            target.setType(item.getType());
            target.setName(item.getName());
            target.setUrl(item.getUrl());
            return target;
        }).toList());
        vo.setContext(detail.getContext().stream().map(item -> {
            AdminContentDetailVO.ContextItem target = new AdminContentDetailVO.ContextItem();
            target.setLabel(item.getLabel());
            target.setValue(item.getValue());
            return target;
        }).toList());
        vo.setHistory(detail.getHistory().stream().map(item -> {
            AdminContentDetailVO.HistoryItem target = new AdminContentDetailVO.HistoryItem();
            target.setTitle(item.getTitle());
            target.setDetail(item.getDetail());
            target.setOperator(item.getOperator());
            target.setCreatedAt(item.getCreatedAt());
            target.setTone(item.getTone());
            return target;
        }).toList());
        return vo;
    }

    private AdminContentListVO toListVO(AdminContentListBO item) {
        AdminContentListVO vo = new AdminContentListVO();
        copyListFields(item, vo);
        return vo;
    }

    private void copyListFields(AdminContentListBO item, AdminContentListVO vo) {
        vo.setId(item.getId().toString());
        vo.setType(item.getType());
        vo.setTitle(item.getTitle());
        vo.setContentPreview(item.getContentPreview());
        vo.setAuthorName(item.getAuthorName());
        vo.setAuthorId(item.getAuthorId().toString());
        vo.setStatus(item.getStatus());
        vo.setAuditStatus(item.getAuditStatus());
        vo.setIsPinned(item.getPinned());
        vo.setIsFeatured(item.getFeatured());
        vo.setReportCount(item.getReportCount());
        AdminContentListVO.Metrics metrics = new AdminContentListVO.Metrics();
        metrics.setViews(item.getViewCount());
        metrics.setLikes(item.getLikeCount());
        metrics.setComments(item.getCommentCount());
        metrics.setShares(item.getShareCount());
        vo.setMetrics(metrics);
        vo.setPublishedAt(item.getPublishedAt());
        vo.setUpdatedAt(item.getUpdatedAt());
        vo.setAvailableActions(item.getAvailableActions());
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
