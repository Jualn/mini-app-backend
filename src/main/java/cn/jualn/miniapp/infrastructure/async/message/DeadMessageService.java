package cn.jualn.miniapp.infrastructure.async.message;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class DeadMessageService {
    private final DeadMessageMapper mapper;

    @Transactional
    public void record(String recordId, MessageEnvelope envelope, int deliveryCount,
                       String category, Throwable failure) {
        DeadMessageRecord record = new DeadMessageRecord();
        record.setStreamKey("jualn:async:events:v1");
        record.setGroupName("backend-events-v1");
        record.setRecordId(recordId);
        if (envelope != null) {
            record.setMessageId(envelope.messageId());
            record.setOperationId(envelope.operationId());
            record.setTopic(envelope.topic());
        }
        record.setErrorCategory(category);
        record.setErrorDetail(sanitize(failure));
        record.setDeliveryCount(Math.max(deliveryCount, 1));
        mapper.upsert(record);
    }

    @Transactional(readOnly = true)
    public List<DeadMessageRecord> listRecent(int limit) {
        return mapper.selectRecent(Math.min(Math.max(limit, 1), 100));
    }

    private String sanitize(Throwable failure) {
        return failure.getClass().getSimpleName();
    }
}
