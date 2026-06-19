package cn.jualn.miniapp.module.comment.controller;

import cn.jualn.miniapp.common.result.PageResult;
import cn.jualn.miniapp.common.result.Result;
import cn.jualn.miniapp.module.comment.converter.CommentConverter;
import cn.jualn.miniapp.module.comment.dto.request.CommentCreateRequest;
import cn.jualn.miniapp.module.comment.dto.request.CommentPageQuery;
import cn.jualn.miniapp.module.comment.service.CommentService;
import cn.jualn.miniapp.module.comment.vo.CommentVO;
import cn.jualn.miniapp.module.comment.vo.ReplyVO;
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
    private final CommentConverter commentConverter;

    /**
     * Create comment.
     */
    @PostMapping
    public Result<Long> createComment(@Valid @RequestBody CommentCreateRequest request) {
        return Result.ok(commentService.createComment(commentConverter.toCreateBO(request)));
    }

    /**
     * 一级评论分页
     */
    @GetMapping
    public Result<PageResult<CommentVO>> pageComment(@Valid CommentPageQuery query) {
        return Result.ok(commentService.pageComment(commentConverter.toPageBO(query)));
    }

    /**
     * 某一级评论的回复分页
     */
    @GetMapping("/replies")
    public Result<PageResult<ReplyVO>> pageReply(@Valid CommentPageQuery query) {
        return Result.ok(commentService.pageReply(commentConverter.toPageBO(query)));
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
