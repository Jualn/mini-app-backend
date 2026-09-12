package cn.jualn.miniapp.module.activity.service.impl;

import cn.jualn.miniapp.common.constant.RedisKeyConstant;
import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.infrastructure.validator.TargetValidator;
import cn.jualn.miniapp.module.activity.bo.ActivityEnrollBO;
import cn.jualn.miniapp.module.activity.entity.Activity;
import cn.jualn.miniapp.module.activity.entity.ActivityEnrollment;
import cn.jualn.miniapp.module.activity.mapper.ActivityEnrollmentMapper;
import cn.jualn.miniapp.module.activity.mapper.ActivityMapper;
import cn.jualn.miniapp.module.activity.service.ActivityEnrollmentService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * 活动订阅业务实现类。
 *
 * <p>实现用户对活动的报名、取消报名和通知开关管理，使用Redis缓存降低高频查询开销。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ActivityEnrollmentServiceImpl implements ActivityEnrollmentService {

    private static final Integer STATUS_ACTIVE = 1;
    private static final Integer STATUS_CANCELLED = 2;
    private static final Duration CACHE_NULL_TTL = Duration.ofSeconds(30);

    private final ActivityEnrollmentMapper activityEnrollmentMapper;
    private final ActivityMapper activityMapper;
    private final RedisService redisService;
    private final TargetValidator targetValidator;

    /**
     * 报名活动。
     *
     * <p>如果存在历史报名记录则复用并恢复状态，避免重复插入；否则创建新记录。</p>
     *
     * @param activityId 活动id
     * @throws BusinessException 当用户未登录、活动不存在或已报名时抛出
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void enrollActivity(Long activityId) {
        Long userId = requireUserId();
        var event = activityMapper.selectForUpdate(activityId);
        if (event == null || !Integer.valueOf(1).equals(event.getPublishStatus()))
            throw new BusinessException(ResultCode.NOT_FOUND, "事项未发布或不可订阅");
        var existing = activityEnrollmentMapper.selectOne(new LambdaQueryWrapper<ActivityEnrollment>()
                .eq(ActivityEnrollment::getActivityId, activityId).eq(ActivityEnrollment::getUserId, userId));
        if (existing == null) {
            if (activityEnrollmentMapper.insert(ActivityEnrollment.builder().activityId(activityId).userId(userId)
                    .status(1).notifyEnable(1).type(2).build()) != 1)
                throw new BusinessException(ResultCode.INVALID_OPERATION, "订阅保存失败");
        } else if (!Integer.valueOf(1).equals(existing.getStatus())) {
            if (activityEnrollmentMapper.update(null, new LambdaUpdateWrapper<ActivityEnrollment>()
                    .set(ActivityEnrollment::getStatus, 1).eq(ActivityEnrollment::getId, existing.getId())) != 1)
                throw new BusinessException(ResultCode.INVALID_OPERATION, "订阅恢复失败");
        }
        invalidateSubscriptionAfterCommit(activityId, userId);
    }

    /**
     * 取消报名活动。
     *
     * <p>采用状态变更（status=2）而不是删除记录，便于后续恢复报名。</p>
     *
     * @param activityId 活动ID
     * @throws BusinessException 当用户未登录或报名记录不存在时抛出
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void unEnrollActivity(Long activityId) {
        Long userId = requireUserId();
        // Lock the same item as subscribe; cancellation remains possible after take-down.
        activityMapper.selectForUpdate(activityId);
        activityEnrollmentMapper.update(null, new LambdaUpdateWrapper<ActivityEnrollment>()
                .set(ActivityEnrollment::getStatus, STATUS_CANCELLED)
                .eq(ActivityEnrollment::getActivityId, activityId).eq(ActivityEnrollment::getUserId, userId));
        invalidateSubscriptionAfterCommit(activityId, userId);
    }

    /**
     * 检查当前用户是否已报名。
     *
     * @param activityId 活动ID
     * @return 已报名返回true，否则返回false
     */
    @Override
    public boolean isEnrolled(Long activityId) {
        if (activityId == null) return false;
        Long userId = requireUserId();
        String cacheKey = RedisKeyConstant.activityEnrollment(activityId, userId);

        String cached = redisService.getString(cacheKey);
        if (redisService.isNullPlaceholder(cached)) {
            log.warn("[活动订阅] 用户 {} 活动 {} 订阅状态缓存命中：未订阅", userId, activityId);
            return false;
        }

        if ("1".equals(cached)) {
            return true;
        }

        ActivityEnrollment enrollment = activityEnrollmentMapper.selectOne(
                new LambdaQueryWrapper<ActivityEnrollment>()
                        .select(ActivityEnrollment::getStatus)
                        .eq(ActivityEnrollment::getActivityId, activityId)
                        .eq(ActivityEnrollment::getUserId, userId)
        );

        if (enrollment == null) {
            redisService.setNullPlaceholder(cacheKey, CACHE_NULL_TTL);
            return false;
        }

        if (Objects.equals(enrollment.getStatus(), STATUS_ACTIVE)) {
            redisService.set(cacheKey, "1", RedisKeyConstant.ACTIVITY_ENROLL_TTL);
            return true;
        }
        return false;
    }

    /**
     * 获取当前用户在指定活动上的报名记录。
     *
     * <p>先查缓存，未命中再查数据库并回填缓存。</p>
     *
     * @param activityId 活动ID
     * @return 报名记录，不存在返回null
     */
    @Override
    public ActivityEnrollment getEnrollment(Long activityId) {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return null;
        }
        if (activityId == null) {
            return null;
        }

        String cacheKey = RedisKeyConstant.activityEnrollment(activityId, userId);
        ActivityEnrollment cached = redisService.get(cacheKey, ActivityEnrollment.class);
        if (cached != null) {
            return cached;
        }

        ActivityEnrollment enrollment = activityEnrollmentMapper.selectByActivityIdAndUserId(activityId, userId);
        if (enrollment != null) {
            cacheEnrollment(enrollment);
        }
        return enrollment;
    }

    /**
     * 更新报名通知开关。
     *
     * @param command 通知更新参数
     * @throws BusinessException 当用户未登录或报名记录不存在时抛出
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateNotifyEnable(ActivityEnrollBO command) {
        Long userId = requireUserId();
        ActivityEnrollment enrollment = getEnrollment(command.getActivityId());
        if (enrollment == null) {
            throw new BusinessException(ResultCode.SUBSCRIBE_NOT_FOUND, "订阅记录不存在");
        }

        enrollment.setNotifyEnable(command.getNotifyEnable());
        activityEnrollmentMapper.updateById(enrollment);
        cacheEnrollment(enrollment);
        log.info("[活动订阅] 用户 {} 更新活动 {} 通知状态为 {}", userId, command.getActivityId(), command.getNotifyEnable());
    }

    @Override
    public List<Long> listEnrolledUserIds(Long activityId, long lastId, int limit) {
        if (activityId == null) return java.util.List.of();
        return activityEnrollmentMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ActivityEnrollment>()
                        .select(ActivityEnrollment::getUserId)
                        .eq(ActivityEnrollment::getActivityId, activityId)
                        .eq(ActivityEnrollment::getStatus, 1)
                        .eq(ActivityEnrollment::getNotifyEnable, 1)
                        .gt(lastId > 0, ActivityEnrollment::getUserId, lastId)
                        .orderByAsc(ActivityEnrollment::getUserId)
                        .last(" LIMIT " + limit)
        ).stream().map(ActivityEnrollment::getUserId).toList();
    }

    @Override
    public Set<Long> listEnrolledActivityIds(List<Long> activityIds) {
        Long userId = UserContext.getUserId();
        if (userId == null || activityIds == null || activityIds.isEmpty()) {
            return Set.of();
        }
        List<Long> enrolledIds = activityEnrollmentMapper.selectEnrolledActivityIds(userId, activityIds);
        if (enrolledIds == null || enrolledIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(enrolledIds);
    }

    private void invalidateSubscriptionAfterCommit(Long id, Long userId) {
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override public void afterCommit() {
                        try { redisService.delete(RedisKeyConstant.activityEnrollment(id, userId)); }
                        catch (RuntimeException e) { log.error("订阅缓存失效失败，id={}", id, e); }
                    }
                });
    }

    private Long requireUserId() {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        return userId;
    }

    @Override
    public long countActiveSubscribers(Long activityId) {
        return activityEnrollmentMapper.selectCount(
                new LambdaQueryWrapper<ActivityEnrollment>()
                        .eq(ActivityEnrollment::getActivityId, activityId)
                        .eq(ActivityEnrollment::getStatus, STATUS_ACTIVE));
    }

    @Override
    public long countNotifyEnabledSubscribers(Long activityId) {
        return activityEnrollmentMapper.selectCount(
                new LambdaQueryWrapper<ActivityEnrollment>()
                        .eq(ActivityEnrollment::getActivityId, activityId)
                        .eq(ActivityEnrollment::getStatus, STATUS_ACTIVE)
                        .eq(ActivityEnrollment::getNotifyEnable, 1));
    }

    private void requireActivityExists(Long activityId) {
        if (activityId == null) {
            throw new BusinessException(ResultCode.ACTIVITY_PARAM_INVALID, "activityId 不能为空");
        }
        String cacheKey = RedisKeyConstant.targetExists(TargetType.ACTIVITY.getKey(), activityId);
        String cached = redisService.getString(cacheKey);
        if ("1".equals(cached)) {
            return;
        }
        if (redisService.isNullPlaceholder(cached)) {
            throw new BusinessException(ResultCode.ACTIVITY_NOT_FOUND, "活动不存在");
        }

        boolean exists = activityMapper.exists(
                new LambdaQueryWrapper<Activity>()
                        .eq(Activity::getId, activityId)
                        .isNull(Activity::getDeletedAt)
        );
        if (exists) {
            redisService.set(cacheKey, "1", RedisKeyConstant.TARGET_EXIST_TTL);
        } else {
            redisService.setNullPlaceholder(cacheKey, RedisKeyConstant.TARGET_EXIST_TTL);
        }
        if (!exists) {
            throw new BusinessException(ResultCode.ACTIVITY_NOT_FOUND, "活动不存在");
        }
    }

    private void cacheEnrollment(ActivityEnrollment enrollment) {
        if (enrollment == null || enrollment.getActivityId() == null || enrollment.getUserId() == null) {
            return;
        }
        redisService.set(
                RedisKeyConstant.activityEnrollment(enrollment.getActivityId(), enrollment.getUserId()),
                enrollment,
                RedisKeyConstant.ACTIVITY_ENROLL_TTL
        );
    }

}
