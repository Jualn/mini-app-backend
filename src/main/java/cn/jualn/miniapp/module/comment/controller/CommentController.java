package cn.jualn.miniapp.module.comment.controller;

import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.result.PageResult;
import cn.jualn.miniapp.common.result.Result;
import cn.jualn.miniapp.module.comment.converter.CommentConverter;
import cn.jualn.miniapp.module.comment.dto.request.CommentCreateRequest;
import cn.jualn.miniapp.module.comment.dto.request.CommentPageQuery;
import cn.jualn.miniapp.module.comment.service.CommentQueries;
import cn.jualn.miniapp.module.comment.service.CommentService;
import cn.jualn.miniapp.module.comment.vo.CommentVO;
import cn.jualn.miniapp.module.comment.vo.ReplyVO;
import cn.jualn.miniapp.module.media.service.MediaService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Comment APIs.
 */
@Validated
@RestController
@RequestMapping("/v1/comment")
@RequiredArgsConstructor
public class CommentController {

    private final CommentService commentService;
    private final cn.jualn.miniapp.module.comment.service.CommunityCommentService community;
    private final cn.jualn.miniapp.module.post.service.PostPublicAccess posts;
    private final CommentQueries queries;
    private final MediaService media;
    private final CommentConverter commentConverter;

    /**
     * Create comment.
     */
    @PostMapping
    public Object createComment(@Valid @RequestBody CommentCreateRequest request, @org.springframework.web.bind.annotation.RequestHeader(value="Idempotency-Key",required=false) String key) {
        var command = commentConverter.toCreateBO(request);
        if(command.getTargetType()==TargetType.POST && posts.isNormal(command.getTargetId())) {
            var submission=community.create(command,key);var result=Result.ok(submission.id());
            return new CreationResult(result.getCode(),result.getMessage(),result.getData(),result.getTimestamp(),result.getTraceId(),submission);
        }
        if (command.getImageObjectKey() != null && !command.getImageObjectKey().isBlank())
            command.setImageObjectKey(media.prepareOwnedImage(TargetType.COMMENT, command.getImageObjectKey()));
        return Result.ok(commentService.createComment(command));
    }

    public record CreationResult(Integer code,String message,Object data,Long timestamp,String traceId,cn.jualn.miniapp.module.comment.bo.CommunityCommentBO.Submission submission) {}

    /**
     * 一级评论分页
     */
    @GetMapping
    public Object pageComment(@Valid CommentPageQuery query, @org.springframework.web.bind.annotation.RequestParam(defaultValue="legacy") String representation) {
        if("thread".equals(representation) && query.getTargetType()!=TargetType.POST) throw cn.jualn.miniapp.module.post.PostProblems.invalid("targetType","INVALID_VALUE","Thread representation requires POST");
        if("thread".equals(representation)) return Result.ok(community.roots(query.getTargetId(),query.getLastId(),query.getPageSize()));
        if(!"legacy".equals(representation)) throw cn.jualn.miniapp.module.post.PostProblems.invalid("representation","INVALID_VALUE","Unsupported representation");
        var page = queries.pageComment(commentConverter.toPageBO(query));
        return Result.ok(PageResult.of(commentConverter.toVOList(page.getList()), page.getHasMore(), page.getNextCursor()));
    }

    /**
     * 某一级评论的回复分页
     */
    @GetMapping("/replies")
    public Object pageReply(@Valid CommentPageQuery query, @org.springframework.web.bind.annotation.RequestParam(defaultValue="legacy") String representation,@org.springframework.web.bind.annotation.RequestParam(required=false) String cursor) {
        if("thread".equals(representation) && query.getTargetType()!=TargetType.POST) throw cn.jualn.miniapp.module.post.PostProblems.invalid("targetType","INVALID_VALUE","Thread representation requires POST");
        if("thread".equals(representation)) return Result.ok(community.replies(query.getTargetId(),query.getParentId(),query.getLastId(),query.getPageSize(),cursor));
        if(!"legacy".equals(representation) || cursor!=null) throw cn.jualn.miniapp.module.post.PostProblems.invalid("representation","INVALID_VALUE","Unsupported representation");
        var page = queries.pageReply(commentConverter.toPageBO(query));
        return Result.ok(PageResult.of(commentConverter.toReplyVOList(page.getList()), page.getHasMore(), page.getNextCursor()));
    }

    /**
     * Remove comment (soft delete).
     */
    @DeleteMapping("/{commentId}")
    public Result<Void> removeComment(@PathVariable @NotNull Long commentId) {
        commentService.removeComment(commentId);
        return Result.ok(null);
    }
}
