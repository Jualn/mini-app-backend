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

    /** Unbound registered media is scoped by the uploader's durable purpose, never by a requested target. */
    public TargetType registeredPurpose(Long attachmentId, long operatorId) {
        return TargetType.fromCode(recordMapper.selectRegisteredPurpose(attachmentId, operatorId));
    }
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
                .gt(MediaUploadRecord::getCleanupAfter, LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"))))) {
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
                distinctKeys, userId, targetType.getCode(), targetId, LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")));
        if (updated != distinctKeys.size()) {
            throw new BusinessException(ResultCode.INVALID_OPERATION, "上传记录不存在、已过期或已被绑定");
        }
    }

    /** Prepares immutable, inspected bytes before a business transaction starts. */
    public String prepareVerified(Long actor, TargetType type, String source, String kind) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Prepare media before starting the business transaction");
        var row = recordMapper.selectOne(new LambdaQueryWrapper<MediaUploadRecord>().eq(MediaUploadRecord::getObjectKey,source));
        if(row==null || !source.equals(row.getObjectKey()) || !java.util.Objects.equals(row.getUserId(),actor) || !java.util.Objects.equals(row.getTargetType(),type.getCode())
                || row.getStatus()!=PENDING || row.getCleanupStartedAt()!=null || !row.getCleanupAfter().isAfter(LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"))))
            throw new BusinessException(ResultCode.INVALID_OPERATION,"上传引用不可用");
        if("VERIFIED".equals(row.getDeclaredKind()) && source.startsWith(MediaObjectKeys.retainedPrefix(type,actor))) { requireKind(row,kind);return source; }
        String snapshot=MediaObjectKeys.retainedPrefix(type,actor)+java.util.UUID.randomUUID();
        recordPending(actor,type,List.of(snapshot),row.getCleanupAfter());
        ManagedAttachmentInspector.Metadata metadata;
        try {
            cosService.copyObject(source,snapshot);
            metadata=new ManagedAttachmentInspector(cosService).inspect(snapshot,kind);
        } catch(cn.jualn.miniapp.common.exception.ContractProblemException problem) {
            if("/problems/attachment-not-ready".equals(problem.getType()))
                throw new BusinessException(ResultCode.INVALID_OPERATION,"上传内容尚未就绪");
            throw problem;
        } catch(cn.jualn.miniapp.common.exception.ExternalServiceException e) {throw AttachmentRegistrationService.unavailable(e);}
        if(recordMapper.update(null,new LambdaUpdateWrapper<MediaUploadRecord>()
                .set(MediaUploadRecord::getDeclaredKind,"VERIFIED").set(MediaUploadRecord::getDeclaredContentType,metadata.contentType())
                .set(MediaUploadRecord::getDeclaredSizeBytes,metadata.sizeBytes()).eq(MediaUploadRecord::getObjectKey,snapshot)
                .eq(MediaUploadRecord::getStatus,PENDING).isNull(MediaUploadRecord::getCleanupStartedAt)
                .gt(MediaUploadRecord::getCleanupAfter,LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"))))!=1)
            throw new BusinessException(ResultCode.INVALID_OPERATION,"上传已过期");
        return snapshot;
    }

    private void requireKind(MediaUploadRecord row,String kind) {
        String contentType=row.getDeclaredContentType();
        boolean matches=contentType!=null && switch(kind) {
            case "IMAGE","POSTER","QR_CODE" -> contentType.startsWith("image/");
            case "PDF" -> contentType.equals("application/pdf");
            case "WORD" -> java.util.Set.of("application/msword","application/vnd.openxmlformats-officedocument.wordprocessingml.document").contains(contentType);
            default -> false;
        };
        if(!matches) throw new BusinessException(ResultCode.INVALID_OPERATION,"Media kind differs from inspected bytes");
    }
    public void requireVerifiedKind(Long actor,TargetType type,String key,cn.jualn.miniapp.module.media.enums.MediaType kind) {
        var row=recordMapper.selectOne(new LambdaQueryWrapper<MediaUploadRecord>().eq(MediaUploadRecord::getObjectKey,key)
            .eq(MediaUploadRecord::getUserId,actor).eq(MediaUploadRecord::getTargetType,type.getCode()).eq(MediaUploadRecord::getDeclaredKind,"VERIFIED"));
        if(row==null || !key.equals(row.getObjectKey())) throw new BusinessException(ResultCode.INVALID_OPERATION,"Missing technical verification");
        requireKind(row,kind.name());
    }

    public boolean isVerified(Long actor,TargetType type,String key) {
        return recordMapper.exists(new LambdaQueryWrapper<MediaUploadRecord>().eq(MediaUploadRecord::getObjectKey,key)
            .eq(MediaUploadRecord::getUserId,actor).eq(MediaUploadRecord::getTargetType,type.getCode())
            .eq(MediaUploadRecord::getDeclaredKind,"VERIFIED").eq(MediaUploadRecord::getStatus,PENDING)
            .isNull(MediaUploadRecord::getCleanupStartedAt).gt(MediaUploadRecord::getCleanupAfter,LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"))));
    }

    /** Same row as the cleanup claim; always called within the business save transaction. */
    public void retainCandidate(cn.jualn.miniapp.module.media.entity.MediaAttachment attachment) {
        requireTransaction();
        if (attachment.getObjectKey() == null) return;
        var record = recordMapper.selectForUpdate(attachment.getObjectKey());
        if (record == null || record.getStatus() != PENDING || record.getCleanupStartedAt() != null
                || !attachment.getId().equals(record.getBoundAttachmentId())
                || !record.getCleanupAfter().isAfter(LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")))) {
            throw cn.jualn.miniapp.common.exception.ContractProblemException.conflict(
                    "attachment-candidate-expired", "Candidate expired or cleanup already claimed it");
        }
        record.setStatus(BOUND);
        if(recordMapper.updateById(record)!=1) throw new IllegalStateException("Candidate retention failed");
    }

    public int cleanupExpiredBatch() {
        LocalDateTime now = LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"));
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
                if(candidate.getBoundAttachmentId()==null) recordMapper.deleteById(candidate.getId());
                else recordMapper.update(null,new LambdaUpdateWrapper<MediaUploadRecord>()
                        .set(MediaUploadRecord::getStatus,3).eq(MediaUploadRecord::getId,candidate.getId()).eq(MediaUploadRecord::getStatus,CLEANING));
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
                     || java.util.Objects.equals(record.getTargetType(), TargetType.EXAM.getCode())
                     || java.util.Objects.equals(record.getTargetType(), TargetType.OFFICIAL_CONTENT.getCode()))
                || !objectKey.startsWith(MediaObjectKeys.uploadPrefix(TargetType.fromCode(record.getTargetType()),operatorId))) {
            throw new BusinessException(ResultCode.INVALID_OPERATION, "附件上传不属于当前运营身份");
        }
        boolean pending = java.util.Objects.equals(record.getStatus(), PENDING)
                && record.getCleanupStartedAt() == null && record.getBoundTargetId() == null
                && record.getBoundAttachmentId() == null && record.getCleanupAfter().isAfter(LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")));
        boolean registered = (java.util.Objects.equals(record.getStatus(), BOUND) || java.util.Objects.equals(record.getStatus(), PENDING))
                && record.getBoundTargetId() == null && record.getBoundAttachmentId() != null;
        if (!pending && !registered) {
            throw new BusinessException(ResultCode.INVALID_OPERATION, "附件上传已过期、进入清理或被其他资源绑定");
        }
        return record;
    }

    public void bindRegisteredAttachment(MediaUploadRecord record, Long attachmentId) {
        requireTransaction();
        if (record.getBoundAttachmentId() != null) return; // Duplicate metadata registrations retain the first owner.
        if (attachmentId == null || recordMapper.bindAttachment(record.getId(), attachmentId, LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"))) != 1) {
            throw new BusinessException(ResultCode.INVALID_OPERATION, "附件上传无法绑定");
        }
    }

    /** Persist deletion intent in the caller's transaction; the existing worker performs COS deletion later. */
    public void requestDeletion(String objectKey, TargetType targetType, Long targetId) {
        requireTransaction();
        int rows = recordMapper.requestDeletion(objectKey, targetType.getCode(), targetId, LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")),
                cosService.managedPublicUrls(objectKey));
        if (rows != 1) {
            log.warn("[MediaUploadRecordService] 删除意图未接管，targetType={}, targetId={}",
                    targetType, targetId);
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
        log.warn("[MediaUploadRecordService] COS 清理失败，uploadRecordId={}, retryCount={}",
                candidate.getId(), retryCount, ex);
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
