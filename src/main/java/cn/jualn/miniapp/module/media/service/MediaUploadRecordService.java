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
        List<MediaUploadRecord> candidates = recordMapper.selectList(
                new LambdaQueryWrapper<MediaUploadRecord>()
                        .select(MediaUploadRecord::getId, MediaUploadRecord::getObjectKey,
                                MediaUploadRecord::getRetryCount)
                        .eq(MediaUploadRecord::getStatus, PENDING)
                        .le(MediaUploadRecord::getCleanupAfter, now)
                        .orderByAsc(MediaUploadRecord::getId)
                        .last("LIMIT " + CLEANUP_BATCH_SIZE));

        int cleaned = 0;
        for (MediaUploadRecord candidate : candidates) {
            if (!claim(candidate.getId(), now)) {
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

    public void removeRecord(String objectKey) {
        recordMapper.delete(new LambdaQueryWrapper<MediaUploadRecord>()
                .eq(MediaUploadRecord::getObjectKey, objectKey));
    }

    public void scheduleDeletionRetry(String objectKey, RuntimeException ex) {
        LocalDateTime now = LocalDateTime.now();
        String error = truncateError(ex.getMessage());
        recordMapper.update(null, new LambdaUpdateWrapper<MediaUploadRecord>()
                .set(MediaUploadRecord::getStatus, PENDING)
                .set(MediaUploadRecord::getBoundTargetId, null)
                .set(MediaUploadRecord::getCleanupAfter, now.plusMinutes(10))
                .set(MediaUploadRecord::getLastError, error)
                .set(MediaUploadRecord::getUpdatedAt, now)
                .setSql("retry_count = retry_count + 1")
                .eq(MediaUploadRecord::getObjectKey, objectKey)
                .eq(MediaUploadRecord::getStatus, BOUND));
    }

    private boolean claim(Long id, LocalDateTime now) {
        return recordMapper.update(null, new LambdaUpdateWrapper<MediaUploadRecord>()
                .set(MediaUploadRecord::getStatus, CLEANING)
                .set(MediaUploadRecord::getUpdatedAt, now)
                .eq(MediaUploadRecord::getId, id)
                .eq(MediaUploadRecord::getStatus, PENDING)) == 1;
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
