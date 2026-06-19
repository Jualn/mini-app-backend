package cn.jualn.miniapp.module.interact.controller;

import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.result.Result;
import cn.jualn.miniapp.module.interact.dto.request.InteractLikeRequest;
import cn.jualn.miniapp.module.interact.dto.request.InteractShareRequest;
import cn.jualn.miniapp.module.interact.dto.request.InteractViewRequest;
import cn.jualn.miniapp.module.interact.service.InteractService;
import cn.jualn.miniapp.module.interact.vo.LikeCountVO;
import cn.jualn.miniapp.module.interact.vo.LikeStatusVO;
import cn.jualn.miniapp.module.interact.vo.ShareCountVO;
import cn.jualn.miniapp.module.interact.vo.ViewCountVO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 互动行为控制器。
 * <p>
 * 提供点赞、分享、浏览等交互能力。
 */
@Validated
@RestController
@RequestMapping("/v1/interact")
@RequiredArgsConstructor
public class InteractController {

    private final InteractService interactService;

    /**
     * 点赞。
     *
     * @param req 点赞请求
     * @return 提交结果
     */
    @PostMapping("/like")
    public Result<String> like(@Valid @RequestBody InteractLikeRequest req) {
        interactService.like(req.getTargetType(), req.getTargetId());
        return Result.ok(null);
    }

    /**
     * 取消点赞。
     *
     * @param req 点赞请求
     * @return 提交结果
     */
    @DeleteMapping("/like")
    public Result<String> unlike(@Valid @RequestBody InteractLikeRequest req) {
        interactService.unlike(req.getTargetType(), req.getTargetId());
        return Result.ok(null);
    }

    /**
     * 查询当前登录用户是否已点赞。
     *
     * @param targetType 目标类型
     * @param targetId   目标ID
     * @return 点赞状态
     */
    @GetMapping("/liked")
    public Result<LikeStatusVO> isLiked(@RequestParam @NotNull TargetType targetType,
                                        @RequestParam @NotNull Long targetId) {
        return Result.ok(LikeStatusVO.builder()
                .liked(interactService.isLiked(targetType, targetId))
                .build());
    }

    /**
     * 查询目标点赞数量。
     *
     * @param targetType 目标类型
     * @param targetId   目标ID
     * @return 点赞数
     */
    @GetMapping("/like/count")
    public Result<LikeCountVO> getLikeCount(@RequestParam @NotNull TargetType targetType,
                                            @RequestParam @NotNull Long targetId) {
        return Result.ok(LikeCountVO.builder()
                .count(interactService.getLikeCount(targetType, targetId))
                .build());
    }

    /**
     * 提交分享行为。
     *
     * @param req 分享请求
     * @return 提交结果
     */
    @PostMapping("/share")
    public Result<String> share(@Valid @RequestBody InteractShareRequest req) {
        interactService.share(req.getTargetType(), req.getTargetId(), req.getPlatform());
        return Result.ok(null);
    }

    /**
     * 查询目标分享数量。
     *
     * @param targetType 目标类型
     * @param targetId   目标ID
     * @return 分享数
     */
    @GetMapping("/share/count")
    public Result<ShareCountVO> getShareCount(@RequestParam @NotNull TargetType targetType,
                                              @RequestParam @NotNull Long targetId) {
        return Result.ok(ShareCountVO.builder()
                .count(interactService.getShareCount(targetType, targetId))
                .build());
    }

    /**
     * 提交浏览行为。
     *
     * @param req 浏览请求
     * @return 提交结果
     */
    @PostMapping("/view")
    public Result<String> view(@Valid @RequestBody InteractViewRequest req) {
        interactService.view(req.getTargetType(), req.getTargetId());
        return Result.ok(null);
    }

    /**
     * 查询目标浏览量。
     *
     * @param targetType 目标类型
     * @param targetId   目标ID
     * @return 浏览量
     */
    @GetMapping("/view/count")
    public Result<ViewCountVO> getViewCount(@RequestParam @NotNull TargetType targetType,
                                            @RequestParam @NotNull Long targetId) {
        return Result.ok(ViewCountVO.builder()
                .count(interactService.getViewCount(targetType, targetId))
                .build());
    }
}
