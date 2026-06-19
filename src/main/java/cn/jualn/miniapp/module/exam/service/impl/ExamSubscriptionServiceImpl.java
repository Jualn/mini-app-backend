package cn.jualn.miniapp.module.exam.service.impl;

import cn.jualn.miniapp.common.constant.RedisKeyConstant;
import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.infrastructure.validator.TargetValidator;
import cn.jualn.miniapp.module.exam.entity.ExamSubscription;
import cn.jualn.miniapp.module.exam.mapper.ExamSubscriptionMapper;
import cn.jualn.miniapp.module.exam.service.ExamSubscriptionService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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
public class ExamSubscriptionServiceImpl implements ExamSubscriptionService {

    private static final Integer STATUS_ACTIVE = 1;
    private static final Integer STATUS_CANCELLED = 2;
    private static final Duration CACHE_NULL_TTL = Duration.ofSeconds(30);

    private final ExamSubscriptionMapper examSubscriptionMapper;
    private final RedisService redisService;
    private final TargetValidator targetValidator;

    /**
     * 订阅考试信息。
     *
     * @param examId 考试ID
     * @throws BusinessException 用户未登录或考试不存在时抛出异常
     */
    @Override
    public void subscribeExam(Long examId) {
        Long userId = requireUserId();
        targetValidator.assertExists(TargetType.EXAM, examId);

        if (isSubscribed(examId)) {
            throw new BusinessException(ResultCode.SUBSCRIBE_ALREADY, "您已经订阅过此考试"); // 先查询缓存，避免重复订阅
        }

        examSubscriptionMapper.insert(ExamSubscription.builder()
                .examInfoId(examId)
                .userId(userId)
                .build()
        );

        log.info("[考试订阅] 用户 {} 订阅考试 {}", userId, examId);
    }

    /**
     * 取消订阅考试信息。
     *
     * @param examId 考试ID
     * @throws BusinessException 用户未登录或订阅不存在时抛出异常
     */
    @Override
    public void unsubscribeExam(Long examId) {
        Long userId = requireUserId();
        String cacheKey = RedisKeyConstant.examSubscription(examId, userId);
        targetValidator.assertExists(TargetType.EXAM, examId);

        examSubscriptionMapper.update(
                new LambdaUpdateWrapper<ExamSubscription>()
                        .set(ExamSubscription::getStatus, STATUS_CANCELLED)
                        .eq(ExamSubscription::getExamInfoId, examId)
                        .eq(ExamSubscription::getUserId, userId)
        );

        redisService.delete(cacheKey);
        log.info("[考试订阅] 用户 {} 取消订阅考试 {}", userId, examId);
    }

    @Override
    public boolean isSubscribed(Long examId) {
        if (examId == null) return false;
        Long userId = requireUserId();

        String cacheKey = RedisKeyConstant.examSubscription(examId, userId);
        String cached = redisService.getString(cacheKey);
        if (redisService.isNullPlaceholder(cached)) {
            log.warn("[考试订阅] 用户 {} 考试 {} 订阅状态缓存命中：未订阅", userId, examId);
            return false;
        }

        if ("1".equals(cached)) {
            return true;
        }

        ExamSubscription subscription = examSubscriptionMapper.selectOne(
                new LambdaQueryWrapper<ExamSubscription>()
                        .select(ExamSubscription::getStatus)
                        .eq(ExamSubscription::getExamInfoId, examId)
                        .eq(ExamSubscription::getUserId, userId)
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
        return examSubscriptionMapper.selectObjs(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ExamSubscription>()
                        .select(ExamSubscription::getUserId)
                        .eq(ExamSubscription::getExamInfoId, examId)
                        .gt(lastId > 0, ExamSubscription::getId, lastId)
                        .orderByAsc(ExamSubscription::getId)
                        .last(" LIMIT " + limit)
        );
    }

    private Long requireUserId() {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        return userId;
    }

}

