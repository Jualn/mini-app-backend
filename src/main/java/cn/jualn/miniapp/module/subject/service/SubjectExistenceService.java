package cn.jualn.miniapp.module.subject.service;

import cn.jualn.miniapp.module.authorization.service.CurrentAuthorization;

import cn.jualn.miniapp.common.constant.RedisKeyConstant;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.module.article.service.ArticleVisibility;
import cn.jualn.miniapp.module.comment.service.CommentSubjectVisibility;
import cn.jualn.miniapp.module.subject.mapper.SubjectExistenceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 目标实体存在性校验组件。
 *
 * <h2>职责边界</h2>
 * <p>本组件只做一件事：判断某个目标实体是否存在，并将结果缓存到 Redis。</p>
 *
 * <p>以下校验不在本组件负责：</p>
 * <ul>
 *   <li>归属权（是否是本人内容）→ Service 加载实体后在内存中判断，一次查询解决</li>
 *   <li>管理操作授权 → CurrentAuthorization / RBAC Reader；存在性缓存不提供权限</li>
 *   <li>账号封禁状态 → 登录拦截器或 AOP 统一处理</li>
 *   <li>业务状态（活动是否可报名、帖子是否可编辑）→ 各模块的 XxxValidator 负责</li>
 * </ul>
 *
 * <h2>适用场景</h2>
 * <p>只适合"操作只需确认目标存在，后续不需要加载实体数据"的情况：</p>
 * <pre>
 *   点赞/评论/浏览 → 只需知道目标存在，不需要实体内容 → 用 assertExists()
 *
 *   编辑/删除/报名 → Service 必须 selectById 拿数据，顺手判 null 即可，
 *                   不要额外调本组件，否则多一次无意义的 DB 查询
 * </pre>
 *
 * <h2>缓存三态</h2>
 * <pre>
 *   null       → 缓存未命中，需查 DB
 *   "1"        → 确认存在，直接放行
 *   "__NULL__" → 确认不存在（防穿透占位），直接拒绝
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SubjectExistenceService {

    /**
     * 确认不存在时的占位符 TTL，短于存在时的 TTL，兼顾防穿透与数据实时性
     */
    private static final Duration NOT_EXISTS_TTL = Duration.ofSeconds(30);

    private final RedisService redisService;
    private final SubjectExistenceMapper subjects;
    private final CommentSubjectVisibility comments;
    private final ArticleVisibility articleVisibility;

    public void assertArticleComment(long id) {
        if (!comments.articleCommentVisible(id)) throw new ContractProblemException(
                HttpStatus.NOT_FOUND, "/problems/not-found", "Comment is unavailable");
    }

    // -------------------------------------------------------------------------
    // 存在性断言
    // -------------------------------------------------------------------------

    /**
     * 断言目标实体存在，不存在则抛出 NOT_FOUND 异常。
     *
     * <p>"存在"仅表示记录在 DB 中存在且未被软删除，
     * 不代表业务状态合法（审核通过、活动报名中等），
     * 业务状态由调用方在加载实体后自行判断。</p>
     *
     * @param targetType 目标类型
     * @param targetId   目标 ID
     * @throws BusinessException NOT_FOUND 目标不存在或已删除
     */
    public void assertExists(TargetType targetType, Long targetId) {
        if (!resolveExists(targetType, targetId)) {
            throw new BusinessException(ResultCode.TARGET_NOT_FOUND,
                    targetType.getDesc() + "不存在");
        }
    }

    /**
     * 批量断言存在，任一不存在则抛出异常
     */
    public void assertAllExist(TargetType targetType, List<Long> targetIds) {
        if (CollectionUtils.isEmpty(targetIds)) return;

        List<Long> notFoundIds = resolveNotExistIds(targetType, targetIds);
        if (!notFoundIds.isEmpty()) {
            throw new BusinessException(ResultCode.TARGET_NOT_FOUND,
                    targetType.getDesc() + "不存在，id=" + notFoundIds);
        }
    }

    /**
     * 判断目标实体是否存在，返回布尔值，不抛异常。
     *
     * <p>适用于需要根据存在性做分支处理的场景，
     * 如通知模块判断关联内容是否仍然有效。
     * 大多数业务操作请用 {@link #assertExists}。</p>
     *
     * @param targetType 目标类型
     * @param targetId   目标 ID
     * @return true 表示存在
     */
    public boolean exists(TargetType targetType, Long targetId) {
        return resolveExists(targetType, targetId);
    }

    // -------------------------------------------------------------------------
    // 缓存失效（内容变更后主动调用）
    // -------------------------------------------------------------------------

    /**
     * 使单个目标的存在性缓存失效。
     *
     * <p>以下场景必须主动调用，否则残留的 "1" 会使已失效内容继续被互动：
     * 软删除、审核拒绝、活动/考试下架或取消。</p>
     *
     * <p>通常与详情缓存一起失效：</p>
     * <pre>{@code
     * targetValidator.invalidateExists(TargetType.POST, postId);
     * redisService.delete(RedisKeyConstant.postDetail(postId));
     * }</pre>
     */
    public void invalidateExists(TargetType targetType, Long targetId) {
        redisService.delete(RedisKeyConstant.targetExists(targetType.getKey(), targetId));
        log.debug("[SubjectExistenceService] 存在性缓存失效，targetType={}, targetId={}",
                targetType, targetId);
    }

    /**
     * 批量使存在性缓存失效。适用于批量审核、批量下架等管理操作。
     */
    public void invalidateExistsBatch(TargetType targetType, List<Long> targetIds) {
        if (targetIds == null || targetIds.isEmpty()) return;
        targetIds.forEach(id -> invalidateExists(targetType, id));
    }

    // -------------------------------------------------------------------------
    // 私有
    // -------------------------------------------------------------------------

    /**
     * 核心解析：读缓存三态 → 未命中则查 DB → 回写缓存。
     */
    private boolean resolveExists(TargetType targetType, Long targetId) {
        if (targetType == TargetType.ARTICLE) return articleVisibility.exists(targetId);
        if (targetType == TargetType.COMMENT) {
            Boolean visible = comments.articleVisibility(targetId);
            if (visible != null) return visible;
        }
        String key = RedisKeyConstant.targetExists(targetType.getKey(), targetId);
        String cached = redisService.getString(key);

        if ("1".equals(cached)) return true;
        if (redisService.isNullPlaceholder(cached)) return false;

        boolean exists = queryExistsFromDB(targetType, targetId);
        if (exists) {
            redisService.set(key, "1", RedisKeyConstant.TARGET_EXIST_TTL);
        } else {
            redisService.setNullPlaceholder(key, NOT_EXISTS_TTL);
        }
        return exists;
    }

    private List<Long> resolveNotExistIds(TargetType targetType, List<Long> targetIds) {
        if (targetType == TargetType.ARTICLE || targetType == TargetType.COMMENT)
            return targetIds.stream().filter(id -> !resolveExists(targetType, id)).toList();
        // 1. 构建 key 列表，保持与 targetIds 顺序一一对应
        List<String> keys = targetIds.stream()
                .map(id -> RedisKeyConstant.targetExists(targetType.getKey(), id))
                .collect(Collectors.toList());

        // 2. 一次 mGET 拿全部缓存值
        Map<String, String> cachedValues = redisService.multiGet(keys, String.class);

        List<Long> cacheMissIds = new ArrayList<>();
        List<Long> confirmedMissIds = new ArrayList<>();

        for (int i = 0; i < targetIds.size(); i++) {
            String cached = cachedValues.get(keys.get(i));
            if ("1".equals(cached)) {
                // 缓存命中：存在，跳过
            } else if (redisService.isNullPlaceholder(cached)) {
                // 缓存命中：确认不存在
                confirmedMissIds.add(targetIds.get(i));
            } else {
                // 缓存未命中：需要查 DB
                cacheMissIds.add(targetIds.get(i));
            }
        }

        // 3. 有已确认不存在的，直接短路返回
        if (!confirmedMissIds.isEmpty()) return confirmedMissIds;

        // 4. 全部命中缓存且存在
        if (cacheMissIds.isEmpty()) return Collections.emptyList();

        // 5. 批量查 DB（一次 IN 查询）
        Set<Long> existsInDb = queryExistsBatchFromDB(targetType, cacheMissIds);

        // 6. 回写缓存
        for (Long id : cacheMissIds) {
            String key = RedisKeyConstant.targetExists(targetType.getKey(), id);
            if (existsInDb.contains(id)) {
                redisService.set(key, "1", RedisKeyConstant.TARGET_EXIST_TTL);
            } else {
                redisService.setNullPlaceholder(key, NOT_EXISTS_TTL);
            }
        }

        // 7. 返回 DB 中也不存在的
        return cacheMissIds.stream()
                .filter(id -> !existsInDb.contains(id))
                .collect(Collectors.toList());
    }


    /**
     * 查询目标在 DB 中是否存在（仅判断软删除标志，不判断业务状态）。
     */
    private boolean queryExistsFromDB(TargetType targetType, Long targetId) {
        requireSupported(targetType);
        return subjects.exists(targetType.getCode(), targetId);
    }

    private Set<Long> queryExistsBatchFromDB(TargetType targetType, List<Long> ids) {
        requireSupported(targetType);
        return subjects.existingIds(targetType.getCode(), ids);
    }

    private void requireSupported(TargetType type) {
        if (type != TargetType.POST && type != TargetType.ACTIVITY && type != TargetType.EXAM
                && type != TargetType.COMMENT && type != TargetType.NOTIFICATION)
            throw new BusinessException(ResultCode.INVALID_TARGET_TYPE, "Unsupported target type: " + type);
    }
}
