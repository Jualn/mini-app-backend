package cn.jualn.miniapp.module.notify.service.inbox;

import cn.jualn.miniapp.common.constant.RedisKeyConstant;
import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.module.notify.enums.NotifyType;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.module.notify.entity.Notification;
import cn.jualn.miniapp.module.notify.mapper.NotificationMapper;
import cn.jualn.miniapp.module.notify.model.NotificationCategory;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

import cn.jualn.miniapp.module.notify.bo.NotificationInboxBO;

@Service
@RequiredArgsConstructor
public class PersonalNotificationInbox {

    private final NotificationMapper notificationMapper;
    private final RedisService redisService;

    public NotificationInboxBO.Page list(NotificationInboxBO.Query query) {
        long userId = requireUserId();
        NotificationInboxBO.Query actual = query == null ? new NotificationInboxBO.Query(null, 20, null, null) : query;
        int pageSize = actual.pageSize() == null ? 20 : actual.pageSize();
        if (pageSize < 1 || pageSize > 50) throw ContractProblemException.validation(
                new ContractProblemException.Violation("query", "pageSize", "RANGE", "pageSize 必须在 1 到 50 之间"));
        NotificationCursorCodec.Position cursor = NotificationCursorCodec.decode(userId, actual);
        List<Notification> rows = notificationMapper.selectCanonicalInbox(userId,
                cursor == null ? null : cursor.createdAt(), cursor == null ? null : cursor.id(),
                typeCodes(actual.category()), actual.isRead(), pageSize + 1);
        boolean hasMore = rows.size() > pageSize;
        List<Notification> page = hasMore ? rows.subList(0, pageSize) : rows;
        List<NotificationInboxBO.Item> items = page.stream().map(this::toItem).toList();
        Notification last = page.isEmpty() ? null : page.get(page.size() - 1);
        String nextCursor = hasMore
                ? NotificationCursorCodec.encode(userId, actual, last.getCreatedAt(), last.getId())
                : null;
        return new NotificationInboxBO.Page(items, nextCursor);
    }

    public long unreadCount() {
        long userId = requireUserId();
        Long count = notificationMapper.countCanonicalUnread(userId);
        return count == null ? 0L : count;
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(String notificationId) {
        long userId = requireUserId();
        long id;
        try { id = Long.parseLong(notificationId); }
        catch (NumberFormatException absent) { return; }
        if (id <= 0) return;
        notificationMapper.ensureInboxCounter(userId);
        notificationMapper.lockInboxHead(userId);
        if (notificationMapper.deleteOwnedInbox(id, userId) > 0) {
            notificationMapper.advanceReadVersion(userId);
            invalidateLegacyUnreadAfterCommit(userId);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void markRead(String notificationId) {
        long userId = requireUserId();
        long id = resourceId(notificationId);
        notificationMapper.ensureInboxCounter(userId);
        notificationMapper.lockInboxHead(userId);
        Integer state = notificationMapper.selectCanonicalReadState(id, userId);
        if (state == null) throw notFound();
        if (state == 0) {
            notificationMapper.markCanonicalRead(id, userId);
            notificationMapper.advanceReadVersion(userId);
            invalidateLegacyUnreadAfterCommit(userId);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void markAllRead() {
        long userId = requireUserId();
        notificationMapper.ensureInboxCounter(userId);
        notificationMapper.lockInboxHead(userId);
        notificationMapper.markAllCanonicalRead(userId);
        notificationMapper.advanceReadVersion(userId);
        invalidateLegacyUnreadAfterCommit(userId);
    }

    private NotificationInboxBO.Item toItem(Notification row) {
        for(var owner:contentAccess){var decision=owner.evaluate(row.getUserId(),row.getTargetType(),row.getTargetId());if(decision.growth())return new NotificationInboxBO.Item(row.getId(),row.getType(),categoryOf(row.getType()),"内容不可用",null,null,row.getReadAt()!=null,row.getCreatedAt());}
        NotifyType type = NotifyType.fromCode(row.getType());
        TargetType targetType = TargetType.fromCode(row.getTargetType());
        NotificationInboxBO.Target target = targetType == null || row.getTargetId() == null ? null
                : new NotificationInboxBO.Target(targetType.name(), row.getTargetId());
        if(type!=null && type.isLearningTask() && row.getTargetId()!=null) {
            target=new NotificationInboxBO.Target("LEARNING_TASK",row.getTargetId());
        }
        return new NotificationInboxBO.Item(row.getId(), row.getType(),
                categoryOf(row.getType()), row.getTitle(), row.getContent(), target,
                row.getReadAt() != null,
                row.getCreatedAt());
    }
    @org.springframework.beans.factory.annotation.Autowired private java.util.List<cn.jualn.miniapp.module.notify.service.NotificationContentAccess> contentAccess;

    private String categoryOf(Integer code) {
        var category = NotificationCategory.of(NotifyType.fromCode(code));
        return category == null ? null : category.name();
    }
    private List<Integer> typeCodes(NotificationCategory category) { return category == null ? null : category.codes(); }

    private long requireUserId() {
        Long userId = UserContext.getUserId();
        if (userId == null) throw new BusinessException(ResultCode.UNAUTHORIZED, "未登录或登录态已过期");
        return userId;
    }

    private long resourceId(String value) {
        try {
            long id = Long.parseLong(value);
            if (id <= 0) throw new NumberFormatException();
            return id;
        } catch (RuntimeException invalid) {
            throw ContractProblemException.validation(new ContractProblemException.Violation(
                    "path", "notificationId", "FORMAT", "notificationId 无效"));
        }
    }

    private ContractProblemException notFound() {
        return new ContractProblemException(HttpStatus.NOT_FOUND, "/problems/not-found", "资源不存在");
    }

    private void invalidateLegacyUnreadAfterCommit(long userId) {
        invalidateAfterCommit(RedisKeyConstant.userUnreadCount(userId));
    }

    private void invalidateAfterCommit(String key) {
        Runnable invalidate = () -> redisService.delete(key);
        if (TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { invalidate.run(); }
            });
        } else {
            invalidate.run();
        }
    }
}
