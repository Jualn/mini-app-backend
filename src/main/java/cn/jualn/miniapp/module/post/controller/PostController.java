package cn.jualn.miniapp.module.post.controller;

import cn.jualn.miniapp.common.result.PageResult;
import cn.jualn.miniapp.common.result.Result;
import cn.jualn.miniapp.module.post.bo.PostListBO;
import cn.jualn.miniapp.module.post.converter.PostConverter;
import cn.jualn.miniapp.module.post.dto.request.PostCreateRequest;
import cn.jualn.miniapp.module.post.dto.request.PostPageQuery;
import cn.jualn.miniapp.module.post.service.PostService;
import cn.jualn.miniapp.module.post.vo.PostDetailVO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * 帖子广场接口。
 */
@Validated
@RestController
@RequestMapping("/v1/post")
@RequiredArgsConstructor
public class PostController {

    private final PostService postService;
    private final PostConverter postConverter;

    /**
     * 发布帖子。
     *
     * @param request 创建请求
      * @return 新帖子列表 BO
     */
    @PostMapping
    public Result<PostListBO> createPost(@Valid @RequestBody PostCreateRequest request) {
        return Result.ok(postService.createPost(postConverter.toCreateBO(request)));
    }

    /**
     * 帖子广场分页列表。
     *
     * @param query 分页查询参数
     * @return 帖子分页结果
     */
    @GetMapping
    public Result<PageResult<PostListBO>> pagePost(@Valid PostPageQuery query) {
        return Result.ok(postService.pagePost(query));
    }

    /**
     * 用户点赞的帖子分页列表。
     *
     * @param userId 用户 ID
     * @param lastLikeId 上一页最后一个点赞记录 ID，首次查询可为空
     * @param pageSize 每页条数
     * @return 帖子分页结果
     */
    @GetMapping("/liked")
    public Result<PageResult<PostListBO>> pageUserLikedPosts(
            @RequestParam @NotNull Long userId,
            @RequestParam(required = false) Long lastLikeId,
            @RequestParam(defaultValue = "20") Integer pageSize) {
        return Result.ok(postService.pageUserLikedPosts(userId, lastLikeId, pageSize));
    }

    /**
     * 帖子详情。
     *
     * @param postId 帖子 ID
     * @return 帖子详情
     */
    @GetMapping("/{postId}")
    public Result<PostDetailVO> getPost(@PathVariable @NotNull Long postId) {
        return Result.ok(
                postService.getPostDetail(postId)
        );
    }

    /**
     * 删除帖子。
     *
     * @param postId 帖子 ID
     * @return 操作结果
     */
    @DeleteMapping("/{postId}")
    public Result<String> removePost(@PathVariable @NotNull Long postId) {
        postService.removePost(postId);
        return Result.ok(null);
    }
}
