package cn.jualn.miniapp.module.media.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class MediaUploadCleanupTask {

    private final MediaUploadRecordService uploadRecordService;

    @Scheduled(cron = "0 */10 * * * ?")
    public void cleanupExpiredUploads() {
        int cleaned = uploadRecordService.cleanupExpiredBatch();
        if (cleaned > 0) {
            log.info("[MediaUploadCleanupTask] 清理完成，count={}", cleaned);
        }
    }
}
