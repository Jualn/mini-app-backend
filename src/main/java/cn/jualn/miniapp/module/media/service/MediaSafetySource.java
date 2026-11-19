package cn.jualn.miniapp.module.media.service;

import cn.jualn.miniapp.module.media.mapper.MediaUploadRecordMapper;
import cn.jualn.miniapp.third.cos.client.CosClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Internal provider input resolution; never exposed through an HTTP signing operation. */
@Service @RequiredArgsConstructor
public class MediaSafetySource {
    private final MediaDeliveryUrls urls;
    private final MediaUploadRecordMapper records;
    private final CosClient cos;
    public String resolve(String reference) {
        // Legacy provider inputs retain their existing path. The caller supplies a durable server job payload.
        if(reference==null || !reference.contains(MediaDeliveryUrls.PATH))return reference;
        Long id=urls.fileId(reference);if(id==null)throw MediaContentService.missing();
        var file=records.selectById(id);
        if(file==null || !file.getObjectKey().startsWith("private/") || !"VERIFIED".equals(file.getDeclaredKind())
                || file.getCleanupStartedAt()!=null || (file.getStatus()!=0 && file.getStatus()!=1)
                || file.getStatus()==0 && !file.getCleanupAfter().isAfter(java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"))))throw MediaContentService.missing();
        return cos.providerReadUrl(file.getObjectKey());
    }
}
