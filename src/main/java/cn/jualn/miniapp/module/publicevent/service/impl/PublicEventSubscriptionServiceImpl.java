package cn.jualn.miniapp.module.publicevent.service.impl;

import cn.jualn.miniapp.common.constant.RedisKeyConstant;
import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.module.publicevent.entity.PublicEventSubscription;
import cn.jualn.miniapp.module.publicevent.mapper.PublicEventSubscriptionMapper;
import cn.jualn.miniapp.module.publicevent.service.PublicEventSubscriptionService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * 考试订阅业务实现类。
 *
 * <p>提供订阅、取消订阅和通知开关更新功能。</p>
 *
 * @author miniapp
 * @since 2026-04-28
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PublicEventSubscriptionServiceImpl implements PublicEventSubscriptionService {

    private static final Integer STATUS_ACTIVE = 1;
    private static final Integer STATUS_CANCELLED = 2;
    private static final Duration CACHE_NULL_TTL = Duration.ofSeconds(30);

    private final cn.jualn.miniapp.module.publicevent.mapper.PublicEventMapper examInfoMapper;
    private final PublicEventSubscriptionMapper examSubscriptionMapper;
    private final RedisService redisService;

    /**
     * 订阅考试信息。
     *
     * @param examId 考试ID
     * @throws BusinessException 用户未登录或考试不存在时抛出异常
     */
    @Override
    public cn.jualn.miniapp.module.publicevent.bo.PublicEventSubscriptionBO getSubscriptionState(Long examId) {
        Long userId = requireUserId();
        PublicEventSubscription relation = examSubscriptionMapper.selectOne(new LambdaQueryWrapper<PublicEventSubscription>()
                .eq(PublicEventSubscription::getExamInfoId, examId).eq(PublicEventSubscription::getUserId, userId));
        if (relation == null) {
            var subject = examInfoMapper.selectByIdNotDeleted(examId);
            if (subject == null || !Integer.valueOf(1).equals(subject.getPublishStatus())) {
                throw new BusinessException(ResultCode.NOT_FOUND);
            }
        }
        boolean subscribed = relation != null && Integer.valueOf(1).equals(relation.getStatus());
        return new cn.jualn.miniapp.module.publicevent.bo.PublicEventSubscriptionBO(subscribed,
                subscribed ? relation.getCreatedAt() : null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public cn.jualn.miniapp.module.publicevent.bo.PublicEventSubscriptionBO subscribeWithState(Long examId) {
        subscribeExam(examId);
        return getSubscriptionState(examId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void subscribeExam(Long examId) {
        Long userId = requireUserId();
        var event = examInfoMapper.selectForUpdate(examId);
        if (event == null || !Integer.valueOf(1).equals(event.getPublishStatus()))
            throw new BusinessException(ResultCode.NOT_FOUND, "事项未发布或不可订阅");
        if (!Integer.valueOf(0).equals(event.getLifecycleStatus()))
            throw ContractProblemException.conflict("lifecycle-conflict", "Only an active public event can be subscribed");
        var existing = examSubscriptionMapper.selectOne(new LambdaQueryWrapper<PublicEventSubscription>()
                .eq(PublicEventSubscription::getExamInfoId, examId).eq(PublicEventSubscription::getUserId, userId));
        if (existing == null) {
            if (examSubscriptionMapper.insert(PublicEventSubscription.builder().examInfoId(examId).userId(userId)
                    .status(1).notifyEnable(1).build()) != 1)
                throw new BusinessException(ResultCode.INVALID_OPERATION, "订阅保存失败");
        } else if (!Integer.valueOf(1).equals(existing.getStatus())) {
            if (examSubscriptionMapper.update(null, new LambdaUpdateWrapper<PublicEventSubscription>()
                    .set(PublicEventSubscription::getStatus, 1).eq(PublicEventSubscription::getId, existing.getId())) != 1)
                throw new BusinessException(ResultCode.INVALID_OPERATION, "订阅恢复失败");
        }
        invalidateSubscriptionAfterCommit(examId, userId);
    }

    /**
     * 取消订阅考试信息。
     *
     * @param examId 考试ID
     * @throws BusinessException 用户未登录或订阅不存在时抛出异常
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void unsubscribeExam(Long examId) {
        Long userId = requireUserId();
        // Lock the same item as subscribe; cancellation remains possible after take-down.
        examInfoMapper.selectForUpdate(examId);
        examSubscriptionMapper.update(null, new LambdaUpdateWrapper<PublicEventSubscription>()
                .set(PublicEventSubscription::getStatus, STATUS_CANCELLED)
                .eq(PublicEventSubscription::getExamInfoId, examId).eq(PublicEventSubscription::getUserId, userId));
        invalidateSubscriptionAfterCommit(examId, userId);
    }

    @Override
    public boolean isSubscribed(Long examId) {
        if (examId == null) return false;
        Long userId = requireUserId();

        String cacheKey = RedisKeyConstant.examSubscription(examId, userId);
        String cached = redisService.getString(cacheKey);
        if (redisService.isNullPlaceholder(cached)) {
            log.debug("[考试订阅] 用户 {} 考试 {} 订阅状态缓存命中：未订阅", userId, examId);
            return false;
        }

        if ("1".equals(cached)) {
            return true;
        }

        PublicEventSubscription subscription = examSubscriptionMapper.selectOne(
                new LambdaQueryWrapper<PublicEventSubscription>()
                        .select(PublicEventSubscription::getStatus)
                        .eq(PublicEventSubscription::getExamInfoId, examId)
                        .eq(PublicEventSubscription::getUserId, userId)
        );
        if (subscription == null) {
            redisService.setNullPlaceholder(cacheKey, CACHE_NULL_TTL);
            return false;
        }

        if (Objects.equals(subscription.getStatus(), STATUS_ACTIVE)) {
            redisService.set(cacheKey, "1", RedisKeyConstant.EXAM_SUBSCRIPTION_TTL);
            return true;
        }

        return false;
    }


    @Override
    public List<Long> listSubscriberUserIds(Long examId, long lastId, int limit) {
        if (examId == null) return java.util.List.of();
        return examSubscriptionMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PublicEventSubscription>()
                        .select(PublicEventSubscription::getUserId)
                        .eq(PublicEventSubscription::getExamInfoId, examId)
                        .eq(PublicEventSubscription::getStatus, 1)
                        .eq(PublicEventSubscription::getNotifyEnable, 1)
                        .gt(lastId > 0, PublicEventSubscription::getUserId, lastId)
                        .orderByAsc(PublicEventSubscription::getUserId)
                        .last(" LIMIT " + limit)
        ).stream().map(PublicEventSubscription::getUserId).toList();
    }

    @Override
    public List<Long> listSubscriberUserIds(Long examId, long lastId, long upperUserId, int limit) {
        if (examId == null || upperUserId <= 0 || limit <= 0) return List.of();
        return examSubscriptionMapper.selectSubscriberUserIdsBounded(
                examId, lastId, upperUserId, Math.min(limit, 100));
    }

    @Override
    public long subscriberUpperBound(Long examId) {
        return examId == null ? 0L : examSubscriptionMapper.selectSubscriberUpperBound(examId);
    }

    private void invalidateSubscriptionAfterCommit(Long id, Long userId) {
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override public void afterCommit() {
                        try { redisService.delete(RedisKeyConstant.examSubscription(id, userId)); }
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

}

