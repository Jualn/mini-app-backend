package cn.jualn.miniapp.module.post.controller;

import cn.jualn.miniapp.common.result.PageResult;
import cn.jualn.miniapp.common.result.Result;
import cn.jualn.miniapp.module.media.service.MediaService;
import cn.jualn.miniapp.module.post.PostProblems;
import cn.jualn.miniapp.module.post.bo.PostListBO;
import cn.jualn.miniapp.module.post.bo.PostVersionedBO;
import cn.jualn.miniapp.module.post.converter.PostConverter;
import cn.jualn.miniapp.module.post.dto.request.PostCreateRequest;
import cn.jualn.miniapp.module.post.dto.request.PostEditRequest;
import cn.jualn.miniapp.module.post.dto.request.PostPageQuery;
import cn.jualn.miniapp.module.post.service.PostEditingService;
import cn.jualn.miniapp.module.post.service.PostQueries;
import cn.jualn.miniapp.module.post.service.PostService;
import cn.jualn.miniapp.module.post.vo.PostDetailVO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
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
    private final cn.jualn.miniapp.module.post.service.PostCommunityService community;
    private final PostQueries queries;
    private final MediaService media;
    private final PostConverter postConverter;
    private final PostEditingService editing;
    private final cn.jualn.miniapp.module.post.service.GrowthRecordAccess growthAccess;
    private final cn.jualn.miniapp.module.post.service.GrowthRecordService growth;

    /**
     * 发布帖子。
     *
     * @param request 创建请求
     * @return 新帖子列表 BO
     */
    @PostMapping
    public Object createPost(@Valid @RequestBody PostCreateRequest request,
            @RequestHeader(value="Idempotency-Key",required=false) String key) {
        var command = postConverter.toCreateBO(request);
        var submission=community.create(command,key);
        var result=Result.ok(community.mine(submission.id()).post());
        return new CreationResult(result.getCode(),result.getMessage(),result.getData(),result.getTimestamp(),result.getTraceId(),submission);
    }
    public record CreationResult(Integer code,String message,cn.jualn.miniapp.module.post.bo.PostDetailBO data,Long timestamp,String traceId,
            cn.jualn.miniapp.module.post.bo.PostCommunityBO.Submission submission) { }

    /**
     * 帖子广场分页列表。
     *
     * @param query 分页查询参数
     * @return 帖子分页结果
     */
    @GetMapping
    public Result<PageResult<PostListBO>> pagePost(@Valid PostPageQuery query) {
        return Result.ok(queries.pagePost(postConverter.toPageBO(query)));
    }

    /**
     * 用户点赞的帖子分页列表。
     *
     * @param userId     用户 ID
     * @param lastLikeId 上一页最后一个点赞记录 ID，首次查询可为空
     * @param pageSize   每页条数
     * @return 帖子分页结果
     */
    @GetMapping("/liked")
    public Result<PageResult<PostListBO>> pageUserLikedPosts(
            @RequestParam @NotNull Long userId,
            @RequestParam(required = false) Long lastLikeId,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) Integer pageSize,
            @RequestParam(defaultValue = "NORMAL") String postType) {
        return Result.ok(queries.pageUserLikedPosts(userId, lastLikeId, pageSize, postType));
    }

    /**
     * 帖子详情。
     *
     * @param postId 帖子 ID
     * @return 帖子详情
     */
    @GetMapping("/{postId}")
    public ResponseEntity<?> getPost(@PathVariable @NotNull Long postId,
                                     @RequestParam(defaultValue = "detail") String view) {
        if ("edit".equals(view)) {
            if(growthAccess.link(postId)!=null) return editable(growth.editable(postId));
            return editable(editing.get(postId));
        }
        if (!"detail".equals(view)) {
            throw PostProblems.invalid("view", "INVALID_VALUE", "Unsupported view");
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .varyBy("Authorization").body(Result.ok(postConverter.toDetailVO(queries.getPostDetail(postId))));
    }

    @PatchMapping("/{postId}")
    public ResponseEntity<?> editPost(@PathVariable long postId,
                                      @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                      @RequestBody PostEditRequest request) {
        if(growthAccess.link(postId)!=null) return editable(growth.edit(postId,new cn.jualn.miniapp.module.post.service.GrowthRecordService.Edit(request.getTitle(),request.getContent(),request.getMediaIds(),request.getCategoryId(),request.getTopicIds(),request.getFields()),ifMatch));
        if(request.getFields().contains("mediaIds")) throw PostProblems.invalid("mediaIds","UNKNOWN_FIELD","Ordinary Post does not accept growth media");
        return editable(editing.edit(postId, postConverter.toEditBO(request), ifMatch));
    }

    /** Native Mini Program compatibility alias; all editing rules remain in the canonical pipeline. */
    @PostMapping("/{postId}:edit")
    public ResponseEntity<?> editPostViaPost(@PathVariable long postId,
                                             @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                             @RequestBody PostEditRequest request) {
        return editPost(postId, ifMatch, request);
    }

    private ResponseEntity<?> editable(PostVersionedBO<?> result) {
        return ResponseEntity.ok().eTag(result.etag()).varyBy("Authorization")
                .cacheControl(CacheControl.noStore()).body(result.value());
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
