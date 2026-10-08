package cn.jualn.miniapp.module.media.service;

import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.module.media.entity.MediaUploadRecord;
import cn.jualn.miniapp.module.media.mapper.MediaUploadRecordMapper;
import cn.jualn.miniapp.third.cos.service.CosService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.CollectionUtils;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class MediaUploadRecordService {

    private static final int PENDING = 0;
    private static final int BOUND = 1;
    private static final int CLEANING = 2;
    private static final int CLEANUP_BATCH_SIZE = 100;

    private final MediaUploadRecordMapper recordMapper;
    private final CosService cosService;
    // Fair traversal only; correctness lives in the conditional claim, not this process-local cursor.
    private final java.util.concurrent.atomic.AtomicLong cleanupCursor = new java.util.concurrent.atomic.AtomicLong();

    /** Preflight only; the short commit transaction repeats eligibility when binding. */
    public void assertPendingProfileUpload(Long userId, String objectKey) {
        if (!recordMapper.exists(new LambdaQueryWrapper<MediaUploadRecord>()
                .eq(MediaUploadRecord::getObjectKey, objectKey)
                .eq(MediaUploadRecord::getUserId, userId)
                .eq(MediaUploadRecord::getTargetType, TargetType.USER.getCode())
                .eq(MediaUploadRecord::getStatus, PENDING)
                .isNull(MediaUploadRecord::getCleanupStartedAt)
                .gt(MediaUploadRecord::getCleanupAfter, LocalDateTime.now()))) {
            throw new BusinessException(ResultCode.INVALID_OPERATION, "上传引用不可用");
        }
    }

    public void recordPending(
            Long userId,
            TargetType targetType,
            Collection<String> objectKeys,
            LocalDateTime cleanupAfter) {
        if (userId == null || targetType == null || cleanupAfter == null
                || CollectionUtils.isEmpty(objectKeys)
                || objectKeys.stream().anyMatch(objectKey -> objectKey == null || objectKey.isBlank())) {
            throw new BusinessException(ResultCode.INVALID_OPERATION, "上传记录参数不完整");
        }
        List<MediaUploadRecord> records = objectKeys.stream()
                .distinct()
                .map(objectKey -> MediaUploadRecord.builder()
                        .objectKey(objectKey)
                        .userId(userId)
                        .targetType(targetType.getCode())
                        .status(PENDING)
                        .cleanupAfter(cleanupAfter)
                        .retryCount(0)
                        .build())
                .toList();
        recordMapper.insertBatch(records);
    }

    public void bindPending(
            Long userId,
            TargetType targetType,
            Long targetId,
            Collection<String> objectKeys) {
        if (CollectionUtils.isEmpty(objectKeys)) {
            return;
        }
        if (userId == null || targetType == null || targetId == null
                || objectKeys.stream().anyMatch(objectKey -> objectKey == null || objectKey.isBlank())) {
            throw new BusinessException(ResultCode.INVALID_OPERATION, "上传绑定参数不完整");
        }
        List<String> distinctKeys = objectKeys.stream().distinct().toList();
        int updated = recordMapper.bindPending(
                distinctKeys, userId, targetType.getCode(), targetId, LocalDateTime.now());
        if (updated != distinctKeys.size()) {
            throw new BusinessException(ResultCode.INVALID_OPERATION, "上传记录不存在、已过期或已被绑定");
        }
    }

    public int cleanupExpiredBatch() {
        LocalDateTime now = LocalDateTime.now();
        resetStaleCleaning(now.minusMinutes(30), now);
        List<MediaUploadRecord> candidates = recordMapper.selectExpiredCandidates(now, cleanupCursor.get(), CLEANUP_BATCH_SIZE);
        if (candidates.isEmpty() && cleanupCursor.get() != 0) {
            cleanupCursor.set(0);
            candidates = recordMapper.selectExpiredCandidates(now, 0, CLEANUP_BATCH_SIZE);
        }

        int cleaned = 0;
        for (MediaUploadRecord candidate : candidates) {
            cleanupCursor.set(candidate.getId());
            if (recordMapper.claimCleanup(candidate.getId(), now, cosService.managedPublicUrls(candidate.getObjectKey())) != 1) {
                continue;
            }
            try {
                cosService.deleteObject(candidate.getObjectKey());
                recordMapper.deleteById(candidate.getId());
                cleaned++;
            } catch (RuntimeException ex) {
                scheduleRetry(candidate, ex, now);
            }
        }
        return cleaned;
    }

    /** Locks the same row cleanup claims; caller owns the registration transaction. */
    public MediaUploadRecord lockAttachmentUpload(Long operatorId, String objectKey) {
        requireTransaction();
        MediaUploadRecord record = recordMapper.selectForUpdate(objectKey);
        if (record == null || !java.util.Objects.equals(record.getUserId(), operatorId)
                || !(java.util.Objects.equals(record.getTargetType(), TargetType.ACTIVITY.getCode())
                     || java.util.Objects.equals(record.getTargetType(), TargetType.EXAM.getCode()))
                || !objectKey.startsWith((java.util.Objects.equals(record.getTargetType(), TargetType.ACTIVITY.getCode())
                     ? "activity/" : "exam/") + operatorId + "/")) {
            throw new BusinessException(ResultCode.INVALID_OPERATION, "附件上传不属于当前运营身份");
        }
        boolean pending = java.util.Objects.equals(record.getStatus(), PENDING)
                && record.getCleanupStartedAt() == null && record.getBoundTargetId() == null
                && record.getBoundAttachmentId() == null && record.getCleanupAfter().isAfter(LocalDateTime.now());
        boolean registered = java.util.Objects.equals(record.getStatus(), BOUND)
                && record.getBoundTargetId() == null && record.getBoundAttachmentId() != null;
        if (!pending && !registered) {
            throw new BusinessException(ResultCode.INVALID_OPERATION, "附件上传已过期、进入清理或被其他资源绑定");
        }
        return record;
    }

    public void bindRegisteredAttachment(MediaUploadRecord record, Long attachmentId) {
        requireTransaction();
        if (record.getBoundAttachmentId() != null) return; // Duplicate metadata registrations retain the first owner.
        if (attachmentId == null || recordMapper.bindAttachment(record.getId(), attachmentId, LocalDateTime.now()) != 1) {
            throw new BusinessException(ResultCode.INVALID_OPERATION, "附件上传无法绑定");
        }
    }

    /** Persist deletion intent in the caller's transaction; the existing worker performs COS deletion later. */
    public void requestDeletion(String objectKey, TargetType targetType, Long targetId) {
        requireTransaction();
        int rows = recordMapper.requestDeletion(objectKey, targetType.getCode(), targetId, LocalDateTime.now(),
                cosService.managedPublicUrls(objectKey));
        if (rows != 1) {
            log.warn("[MediaUploadRecordService] 删除意图未接管，targetType={}, targetId={}, objectKey={}",
                    targetType, targetId, objectKey);
        }
    }

    private void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new cn.jualn.miniapp.common.exception.SystemException("媒体状态必须在业务事务内修改");
        }
    }

    private void scheduleRetry(MediaUploadRecord candidate, RuntimeException ex, LocalDateTime now) {
        int retryCount = candidate.getRetryCount() == null ? 1 : candidate.getRetryCount() + 1;
        long delayMinutes = Math.min(24L * 60L, 5L * (1L << Math.min(retryCount, 8)));
        String error = truncateError(ex.getMessage());
        recordMapper.update(null, new LambdaUpdateWrapper<MediaUploadRecord>()
                .set(MediaUploadRecord::getStatus, PENDING)
                .set(MediaUploadRecord::getRetryCount, retryCount)
                .set(MediaUploadRecord::getCleanupAfter, now.plusMinutes(delayMinutes))
                .set(MediaUploadRecord::getLastError, error)
                .set(MediaUploadRecord::getUpdatedAt, now)
                .eq(MediaUploadRecord::getId, candidate.getId())
                .eq(MediaUploadRecord::getStatus, CLEANING));
        log.warn("[MediaUploadRecordService] COS 清理失败，objectKey={}, retryCount={}",
                candidate.getObjectKey(), retryCount, ex);
    }

    private String truncateError(String error) {
        return error != null && error.length() > 500 ? error.substring(0, 500) : error;
    }

    private void resetStaleCleaning(LocalDateTime staleBefore, LocalDateTime retryAt) {
        recordMapper.update(null, new LambdaUpdateWrapper<MediaUploadRecord>()
                .set(MediaUploadRecord::getStatus, PENDING)
                .set(MediaUploadRecord::getCleanupAfter, retryAt)
                .eq(MediaUploadRecord::getStatus, CLEANING)
                .le(MediaUploadRecord::getUpdatedAt, staleBefore));
    }
}
