package cn.jualn.miniapp.module.notify.service.impl;

import cn.jualn.miniapp.common.constant.RedisKeyConstant;
import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.module.notify.bo.NotificationCenterBO;
import cn.jualn.miniapp.module.notify.entity.Notification;
import cn.jualn.miniapp.module.notify.mapper.NotificationMapper;
import cn.jualn.miniapp.module.notify.service.NotificationCenterService;
import cn.jualn.miniapp.module.notify.service.NotificationSnapshotProjection;
import cn.jualn.miniapp.module.notify.service.NotificationStreamCursorCodec;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.List;

@Service
@RequiredArgsConstructor
public class NotificationCenterServiceImpl implements NotificationCenterService {
    private final NotificationMapper mapper;
    private final NotificationStreamCursorCodec cursors;
    private final NotificationSnapshotProjection projection;
    private final RedisService redis;
    private final TransactionTemplate transactions;
    private final io.micrometer.core.instrument.MeterRegistry meters;

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public NotificationCenterBO.Page list(NotificationCenterBO.Query query) {
        long userId = currentUser();
        validateQuery(query);
        Long before = cursors.decodePage(userId, query);
        long head = mapper.selectInboxHead(userId);
        if (before != null && before > head) { throw NotificationStreamCursorCodec.invalid(); }
        List<Notification> rows = mapper.selectStructuredInbox(userId, head, before, types(query),
                "SYSTEM".equals(query.boxCategory()), query.isRead(), query.pageSize() + 1);
        boolean hasMore = rows.size() > query.pageSize();
        List<Notification> page = hasMore ? rows.subList(0, query.pageSize()) : rows;
        String next = hasMore ? cursors.page(userId, page.get(page.size() - 1).getInboxSeq(), query) : null;
        return new NotificationCenterBO.Page(page.stream().map(projection::item).toList(), next, hasMore, cursors.head(userId, head));
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public NotificationCenterBO.Summary summary(String afterCursor) {
        long userId = currentUser();
        Long after = afterCursor == null ? null : cursors.decodeHead(userId, afterCursor);
        long head = mapper.selectInboxHead(userId);
        if (after != null && after > head) { throw NotificationStreamCursorCodec.invalid(); }
        int unread = Math.toIntExact(mapper.countUnreadThrough(userId, head));
        int arrived = after == null ? 0 : Math.toIntExact(mapper.countNewThrough(userId, after, head));
        NotificationCenterBO.Preview latest = arrived == 0 ? null : projection.preview(mapper.selectLatestThrough(userId, after, head));
        return new NotificationCenterBO.Summary(unread, cursors.head(userId, head), arrived, latest);
    }

    @Override
    public NotificationCenterBO.Item get(String notificationId) {
        long userId = currentUser();
        Notification row = mapper.selectOwned(id(notificationId), userId);
        if (row == null) {
            meters.counter("jualn.notification.recovery", "result", "not_found").increment();
            throw notFound();
        }
        return projection.item(row);
    }

    @Override
    public NotificationCenterBO.ReadResult batchRead(List<String> notificationIds) {
        long userId = currentUser();
        if (notificationIds == null || notificationIds.isEmpty() || notificationIds.size() > 50) {
            throw validation("notificationIds", "批量大小必须为 1–50");
        }
        List<Long> ids = notificationIds.stream().map(this::id).distinct().sorted().toList();
        Integer changed = transactions.execute(status -> {
            mapper.ensureInboxCounter(userId);
            mapper.lockInboxHead(userId);
            if (mapper.lockOwnedIds(userId, ids).size() != ids.size()) { throw notFound(); }
            int count = mapper.markOwnedRead(userId, ids);
            if (count > 0) { mapper.advanceReadVersion(userId); }
            invalidate(userId);
            return count;
        });
        return new NotificationCenterBO.ReadResult(changed, Math.toIntExact(mapper.countCanonicalUnread(userId)));
    }

    @Override
    public NotificationCenterBO.ReadResult readThrough(String throughCursor) {
        long userId = currentUser();
        long boundary = cursors.decodeHead(userId, throughCursor);
        Integer changed = transactions.execute(status -> {
            mapper.ensureInboxCounter(userId);
            if (boundary > mapper.lockInboxHead(userId)) { throw NotificationStreamCursorCodec.invalid(); }
            int count = mapper.markReadThrough(userId, boundary);
            if (count > 0) { mapper.advanceReadVersion(userId); }
            invalidate(userId);
            return count;
        });
        return new NotificationCenterBO.ReadResult(changed, Math.toIntExact(mapper.countCanonicalUnread(userId)));
    }

    private List<Integer> types(NotificationCenterBO.Query query) {
        if (query.category() != null) {
            return switch (query.category()) {
                case ACTIVITY -> List.of(4,8,9,12,13,14,15);
                case PUBLIC_EVENT -> List.of(5,10,11,16,17,18);
            };
        }
        if ("INTERACTION".equals(query.boxCategory())) { return List.of(1,2,3,19,20,21,22); }
        if ("ACTIVITY".equals(query.boxCategory())) { return List.of(4,5,8,9,10,11,12,13,14,15,16,17,18); }
        return null;
    }
    private void validateQuery(NotificationCenterBO.Query query) {
        if (query == null || query.pageSize() < 1 || query.pageSize() > 50
                || query.category() != null && query.boxCategory() != null
                || query.boxCategory() != null && !List.of("INTERACTION", "ACTIVITY", "SYSTEM").contains(query.boxCategory())) {
            throw validation("boxCategory", "列表筛选条件不合法");
        }
    }
    private long id(String value) {
        try {
            if (value == null || value.isBlank() || value.length() > 128) { throw new NumberFormatException(); }
            long parsed = Long.parseLong(value);
            if (parsed <= 0) { throw new NumberFormatException(); }
            return parsed;
        } catch (NumberFormatException invalidId) {
            // A syntactically valid opaque ResourceId that cannot address this provider is simply absent.
            throw notFound();
        }
    }
    private long currentUser() {
        Long id = UserContext.getUserId();
        if (id == null) { throw new BusinessException(ResultCode.UNAUTHORIZED); }
        return id;
    }
    private ContractProblemException notFound() {
        return new ContractProblemException(HttpStatus.NOT_FOUND, "/problems/not-found", "通知不存在或不可访问");
    }
    private ContractProblemException validation(String field, String detail) {
        return ContractProblemException.validation(new ContractProblemException.Violation("query", field, "INVALID", detail));
    }
    private void invalidate(long userId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { redis.delete(RedisKeyConstant.userUnreadCount(userId)); }
        });
    }
}
