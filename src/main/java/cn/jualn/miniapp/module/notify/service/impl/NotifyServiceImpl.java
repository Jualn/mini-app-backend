package cn.jualn.miniapp.module.notify.service.impl;

import cn.jualn.miniapp.common.constant.RedisKeyConstant;
import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.common.enums.WxMpNotifyTemplate;
import cn.jualn.miniapp.common.enums.NotifyType;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.PageResult;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.infrastructure.queue.contract.DelayQueueProducer;
import cn.jualn.miniapp.infrastructure.queue.contract.QueueProducer;
import cn.jualn.miniapp.infrastructure.validator.TargetValidator;
import cn.jualn.miniapp.module.activity.service.ActivityEnrollmentService;
import cn.jualn.miniapp.module.exam.service.ExamSubscriptionService;
import cn.jualn.miniapp.module.notify.bo.NotificationBO;
import cn.jualn.miniapp.module.notify.bo.NotificationPageBO;
import cn.jualn.miniapp.module.notify.converter.NotifyConverter;
import cn.jualn.miniapp.module.notify.entity.Notification;
import cn.jualn.miniapp.module.notify.entity.NotifyPlan;
import cn.jualn.miniapp.module.notify.mapper.NotificationMapper;
import cn.jualn.miniapp.module.notify.mapper.NotifyPlanMapper;
import cn.jualn.miniapp.module.notify.payload.BroadcastPlanPayload;
import cn.jualn.miniapp.module.notify.payload.NotifyPayload;
import cn.jualn.miniapp.module.notify.service.NotifyService;
import cn.jualn.miniapp.module.setting.bo.UserSettingBO;
import cn.jualn.miniapp.module.setting.service.SettingService;
import cn.jualn.miniapp.module.user.service.UserService;
import cn.jualn.miniapp.module.wx.assembler.WxNoticePayloadAssembler;
import cn.jualn.miniapp.module.wx.notice.data.NoticeData;
import cn.jualn.miniapp.module.wx.support.NotifyPlanNoticeDataFactory;
import cn.jualn.miniapp.third.wx.client.WxClient;
import cn.jualn.miniapp.third.wx.config.WxProperties;
import cn.jualn.miniapp.third.wx.dto.MpTemplateMessageRequest;
import cn.jualn.miniapp.third.wx.notice.WxMpNoticeType;
import cn.jualn.miniapp.third.wx.service.WxMpNoticeSendService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotifyServiceImpl implements NotifyService {

    private final NotificationMapper notificationMapper;
    private final NotifyPlanMapper notifyPlanMapper;
    private final ActivityEnrollmentService enrollmentService;
    private final ExamSubscriptionService examSubService;
    private final UserService userService;
    private final SettingService settingService;
    private final RedisService redisService;
    private final TargetValidator targetValidator;
    private final NotifyConverter notifyConverter;
    private final QueueProducer queueProducer;
    private final DelayQueueProducer delayQueueProducer;
    private final WxClient wxClient;
    private final WxProperties wxProperties;

    private static final int BATCH_SIZE = 100;
    private final WxMpNoticeSendService wxMpNoticeSendService;
    private final WxNoticePayloadAssembler wxNoticePayloadAssembler;
    private final NotifyPlanNoticeDataFactory notifyPlanNoticeDataFactory;

    @Override
    public PageResult<NotificationBO> pageCurrentUserNotifications(NotificationPageBO query) {
        Long userId = requireCurrentUserId();
        NotificationPageBO actualQuery = query == null ? new NotificationPageBO() : query;
        int pageSize = normalizePageSize(actualQuery.getPageSize());
        Integer type = actualQuery.getType() != null ? actualQuery.getType().getCode() : null;

        LambdaQueryWrapper<Notification> wrapper = new LambdaQueryWrapper<Notification>()
                .eq(Notification::getUserId, userId)
                .eq(type != null, Notification::getType, type)
                .eq(actualQuery.getIsRead() != null, Notification::getIsRead, actualQuery.getIsRead())
                .lt(actualQuery.getLastId() != null, Notification::getId, actualQuery.getLastId())
                .orderByDesc(Notification::getId)
                .last("LIMIT " + pageSize);

        List<Notification> list = notificationMapper.selectList(wrapper);
        List<NotificationBO> resultList = notifyConverter.toBOList(list);

        PageResult<NotificationBO> result = PageResult.of(resultList, list.size() == pageSize);
        result.setNextCursor(resultList.isEmpty() ? null : resultList.get(resultList.size() - 1).getId());
        return result;
    }

    @Override
    public Long countCurrentUnreadNotifications() {
        Long userId = requireCurrentUserId();
        Long cached = redisService.getLong(RedisKeyConstant.userUnreadCount(userId));
        if (cached != null) {
            return Math.max(cached, 0L);
        }

        long dbCount = notificationMapper.selectCount(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getUserId, userId)
                .eq(Notification::getIsRead, 0));
        redisService.set(RedisKeyConstant.userUnreadCount(userId), dbCount);
        return dbCount;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markAsRead(Long notificationId) {
        Long userId = requireCurrentUserId();

        if (isReadAndOwnedNotification(notificationId, userId)) {
            return;
        }

        notificationMapper.update(new LambdaUpdateWrapper<Notification>()
                .eq(Notification::getId, notificationId)
                .eq(Notification::getUserId, userId)
                .set(Notification::getIsRead, 1));

        decrementUnreadCount(userId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markAllAsRead() {
        Long userId = requireCurrentUserId();
        long unreadCount = notificationMapper.selectCount(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getUserId, userId)
                .eq(Notification::getIsRead, 0));
        if (unreadCount <= 0) {
            redisService.set(RedisKeyConstant.userUnreadCount(userId), 0L);
            return;
        }

        notificationMapper.update(new LambdaUpdateWrapper<Notification>()
                .eq(Notification::getUserId, userId)
                .eq(Notification::getIsRead, 0)
                .set(Notification::getIsRead, 1));
        redisService.set(RedisKeyConstant.userUnreadCount(userId), 0L);
    }

    // 写延迟队列
    @Override
    public void enqueueNotifyPlan(Long planId) {
        NotifyPlan plan = notifyPlanMapper.selectById(planId);
        if (plan == null) {
            log.warn("[DelayQueue] 通知计划不存在，跳过入队，planId={}", planId);
            return;
        }
        if (plan.getStatus() == null || plan.getStatus() != 0) {
            log.debug("[DelayQueue] 通知计划状态非待发送，跳过入队，planId={}, status={}", planId, plan.getStatus());
            return;
        }
        if (plan.getSendAt() == null) {
            log.warn("[DelayQueue] 通知计划 sendAt 为空，跳过入队，planId={}", planId);
            return;
        }

        BroadcastPlanPayload payload = BroadcastPlanPayload.builder()
                .planId(plan.getId())
                .build();
        delayQueueProducer.send(payload, plan.getSendAt(), RedisKeyConstant.noticeQueue(planId));
        log.info("[DelayQueue] 通知计划已入延迟队列，planId={}, sendAt={}", plan.getId(), plan.getSendAt());
    }

    // 延迟队列消费，发送消息队列
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void broadcastPlanFanOut(Long planId) {
        NotifyPlan plan = notifyPlanMapper.selectById(planId);

        if (plan == null || !Integer.valueOf(0).equals(plan.getStatus())) {
            log.debug("[Broadcast] 计划已处理或不存在，planId={}", planId);
            return;
        }

        NoticeData wxData = notifyPlanNoticeDataFactory.build(plan);

        long lastId = 0L;
        int total = 0;

        while (true) {
            List<Long> userIds = queryUserIds(plan, lastId);
            if (userIds.isEmpty()) {
                break;
            }

            for (Long uid : userIds) {
                NotifyPayload payload = buildNotifyPayload(uid, plan, wxData);
                queueProducer.send(payload);
            }

            total += userIds.size();

            if (userIds.size() < BATCH_SIZE) {
                break;
            }

            lastId = userIds.get(userIds.size() - 1);
        }

        notifyPlanMapper.update(new LambdaUpdateWrapper<NotifyPlan>()
                .eq(NotifyPlan::getId, planId)
                .set(NotifyPlan::getStatus, 1)
        );

        // TODO 后续可把 status=1 语义改为 FANOUT_DONE，而不是“已发送”。
        // TODO 后续可加 CAS 抢占，避免多个消费者重复 fan-out。
        log.info("[Broadcast] fan-out 完成，planId={}，共 {} 人", planId, total);
    }

    /**
     * 处理个人通知的 payload，写站内通知表，并尝试推送服务号订阅通知。
     * 最终发布通知的接口，所有个人通知相关的业务场景都应该走这个接口。
     * 不管直接发送还是延迟发送都会汇总到这里，保证站内通知和服务号通知的一致性。
     *
     * @param payload 个人通知负载
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void processNotificationPayload(NotifyPayload payload) {
        if (payload == null || payload.getReceiverId() == null || payload.getType() == null) {
            log.warn("[Notify] payload 非法，跳过，payload={}", payload);
            return;
        }

        if (shouldValidateTarget(payload)
                && !targetValidator.exists(payload.getTargetType(), payload.getTargetId())) {
            log.debug("[Notify] target 不存在，跳过，targetType={}, targetId={}",
                    payload.getTargetType(), payload.getTargetId());
            return;
        }

        Notification notification = Notification.builder()
                .userId(payload.getReceiverId())
                .senderId(payload.getSenderId())
                .type(payload.getType().getCode())
                .title(payload.getTitle())
                .content(payload.getContent())
                .targetType(payload.getTargetType() != null ? payload.getTargetType().getCode() : null)
                .targetId(payload.getTargetId())
                .isRead(0)
                .build();

        notificationMapper.insert(notification);
        redisService.increment(RedisKeyConstant.userUnreadCount(payload.getReceiverId()));

        // TODO 后续补 DB 唯一键 / dedupeKey，避免 MQ 重试导致重复站内通知。
        if (!isNotifyEnabled(payload.getReceiverId(), payload.getType())) {
            log.debug("[Notify] 用户已关闭此类通知，跳过外部推送，userId={}, type={}",
                    payload.getReceiverId(), payload.getType());
            return;
        }

        tryPushMpSubscribeNotice(payload);

        log.info("[Notify] 个人通知处理完成，userId={}, type={}",
                payload.getReceiverId(), payload.getType());
    }

    /**
     * 根据 NotifyPlan 的范围（订阅用户/全体）和来源（活动/考试）查询用户 ID 列表，支持分页查询。
     * 注意：这里不做复杂的权限校验，假设如果用户能参与活动/考试，就应该能收到相关通知。
     *
     * @param plan 通知计划
     * @param lastId 上次查询的最后一个用户 ID，分页使用，初始传 0
     * @return 用户 ID 列表，按 ID 升序排列，最多 BATCH_SIZE 条
     */
    private List<Long> queryUserIds(NotifyPlan plan, long lastId) {
        if (plan == null) return List.of();
        return switch (plan.getScope()) {
            case 0 -> switch (plan.getSourceType()) {
                case 1 -> enrollmentService.listEnrolledUserIds(plan.getSourceId(), lastId, BATCH_SIZE);
                case 2 -> examSubService.listSubscriberUserIds(plan.getSourceId(), lastId, BATCH_SIZE);
                default -> List.of();
            };
            case 1 -> userService.listAllUserIds(lastId, BATCH_SIZE);
            default -> List.of();
        };
    }

    /**
     * 构建 NotifyPayload，传入最终发送通知执行的process
     * @param uid 接收用户 ID
     * @param plan 通知计划
     * @param wxData 通知计划相关的微信通知数据，供后续构建服务号订阅通知使用，避免重复查询业务表
     * @return NotifyPayload
     */
    private NotifyPayload buildNotifyPayload(Long uid, NotifyPlan plan, NoticeData wxData) {
        return NotifyPayload.builder()
                .receiverId(uid)
                .senderId(null)
                .type(NotifyType.fromCode(plan.getNotifyType()))
                .title(plan.getTitle())
                .content(plan.getContent())
                .targetType(sourceTypeToTargetType(plan.getSourceType()))
                .targetId(plan.getSourceId())
                .wxData(wxData)
                .build();
    }

    private TargetType sourceTypeToTargetType(Integer sourceType) {
        if (sourceType == null) {
            return null;
        }

        return switch (sourceType) {
            case 1 -> TargetType.ACTIVITY;
            case 2 -> TargetType.EXAM;
            default -> null;
        };
    }

    private boolean isReadAndOwnedNotification(Long notificationId, Long userId) {
        Integer isRead = notificationMapper.selectIsReadByIdAndUserId(notificationId, userId);
        if (isRead == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "通知不存在");
        }
        return isRead == 1;
    }

    private Long requireCurrentUserId() {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "未登录或登录态已过期");
        }
        return userId;
    }

    private int normalizePageSize(Integer pageSize) {
        if (pageSize == null || pageSize <= 0) {
            return 20;
        }
        return Math.min(pageSize, 50);
    }

    private void decrementUnreadCount(Long userId) {
        Long current = redisService.getLong(RedisKeyConstant.userUnreadCount(userId));
        if (current == null || current <= 0) {
            redisService.set(RedisKeyConstant.userUnreadCount(userId), 0L);
            return;
        }
        redisService.decrement(RedisKeyConstant.userUnreadCount(userId));
    }

    private boolean isNotifyEnabled(Long userId, NotifyType type) {
        UserSettingBO setting = settingService.getSettingByUserId(userId);

        return switch (type.getCode()) {
            case 1 -> Boolean.TRUE.equals(setting.getNotifyComment());
            case 2 -> Boolean.TRUE.equals(setting.getNotifyReply());
            case 3 -> Boolean.TRUE.equals(setting.getNotifyLike());
            case 4 -> Boolean.TRUE.equals(setting.getNotifyActivityRemind());
            case 5 -> Boolean.TRUE.equals(setting.getNotifyExamRemind());
            case 6 -> Boolean.TRUE.equals(setting.getNotifyAuditResult());
            case 7 -> Boolean.TRUE.equals(setting.getNotifySystem());
            default -> false;
        };
    }

    /**
     * 推送服务号订阅通知。
     * <p>
     * 只做最小编排：
     * 1. 根据站内通知类型映射服务号通知类型
     * 2. 构建模板 payload
     * 3. 调用 WxMpNoticeSendService 发送
     * <p>
     * 注意：
     * 这里不维护“用户是否永久授权”的复杂状态。
     * 用户没绑定、没授权、额度不足、模板未配置，都只记录日志并跳过，
     * 不影响站内通知。
     */
    private void tryPushMpSubscribeNotice(NotifyPayload payload) {
        WxMpNoticeType mpNoticeType = toMpNoticeType(payload.getType());

        if (mpNoticeType == null) {
            log.debug("[WeChat MP] 当前通知类型未配置服务号订阅通知，跳过，userId={}, notifyType={}",
                    payload.getReceiverId(), payload.getType());
            return;
        }

        try {
            // 构建服务号通知模板数据，注意这里可能会抛 BusinessException，表示缺少必要字段或模板未启用等问题
            Map<String, Object> wxPayload = wxNoticePayloadAssembler.assemble(payload);

            wxMpNoticeSendService.send(
                    payload.getReceiverId(),
                    mpNoticeType,
                    wxPayload
            );

            log.info("[WeChat MP] 服务号订阅通知已发送，userId={}, type={}",
                    payload.getReceiverId(), mpNoticeType.getKey());

        } catch (BusinessException ex) {
            log.debug("[WeChat MP] 服务号订阅通知跳过，userId={}, notifyType={}, reason={}",
                    payload.getReceiverId(), payload.getType(), ex.getMessage());
        } catch (Exception ex) {
            log.error("[WeChat MP] 服务号订阅通知发送异常，userId={}, notifyType={}",
                    payload.getReceiverId(), payload.getType(), ex);
        }
    }
    /**
     * 站内通知类型 -> 服务号订阅通知类型。
     * <p>
     * 这里不要过度设计。
     * 支持哪些类型，就在 wx.mp.notice-templates 配哪些模板。
     * 没配置的类型会在发送服务里抛 BusinessException，然后被上面捕获跳过。
     */
    private WxMpNoticeType toMpNoticeType(NotifyType type) {
        if (type == null) {
            return null;
        }

        return switch (type.getCode()) {
            case 1 -> WxMpNoticeType.COMMENT_REPLY;     // 评论
            case 2 -> WxMpNoticeType.REPLY;             // 回复
            case 3 -> WxMpNoticeType.LIKE;              // 点赞
            case 4 -> WxMpNoticeType.ACTIVITY_START;    // 活动提醒
            case 5 -> WxMpNoticeType.EXAM_REMIND;       // 考试提醒
            case 6 -> WxMpNoticeType.AUDIT_RESULT;      // 审核结果
            case 7 -> WxMpNoticeType.SYSTEM_NOTICE;     // 系统通知
            default -> null;
        };
    }

    private boolean shouldValidateTarget(NotifyPayload payload) {
        return payload.getTargetType() != null && payload.getTargetId() != null;
    }

    @Deprecated
    private void tryPushWx(Long userId, Long targetId, WxMpNotifyTemplate template, String[] wxValues) {
        // 获取用户微信 openId
        String mpOpenId;
        try {
            mpOpenId = userService.getMiniOpenid(userId);
        } catch (BusinessException ex) {
            log.debug("[WeChat] 用户未绑定微信或无法获取 openid，跳过推送，userId={}", userId);
            return;
        }

        try {
            // 构建模板数据
            Map<String, Map<String, String>> templateData = new HashMap<>();
            String[] fieldKeys = template.getFieldKeys();
            for (int i = 0; i < fieldKeys.length && i < wxValues.length; i++) {
                Map<String, String> field = new HashMap<>();
                field.put("value", wxValues[i]);
                templateData.put(fieldKeys[i], field);
            }

            // 发送微信模板消息
            MpTemplateMessageRequest request = MpTemplateMessageRequest.builder()
                    .toUser(mpOpenId)
                    .templateId(template.getTemplateId())
                    .miniProgram(Map.of(
                            "appid", wxProperties.getMa().getAppId(),
                            "pagepath", template.getPagePath() + "?id=" + targetId
                    ))
                    .data(templateData)
                    .build();
            wxClient.sendMpTemplateMessage(request);
            log.info("[WeChat] 模板消息已发送，userId={}, template={}", userId, template.getTemplateId());
        } catch (Exception ex) {
            log.error("[WeChat] 模板消息发送失败，userId={}, template={}", userId, template.getTemplateId(), ex);
        }
    }
}
