package cn.jualn.miniapp.infrastructure.async.outbox;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class OutboxOperations {
    private final OutboxMapper mapper;

    @Transactional(readOnly = true)
    public OutboxEvent inspect(long eventId) {
        return mapper.selectById(eventId);
    }

    @Transactional(readOnly = true)
    public List<OutboxEvent> listDead(int limit) {
        return mapper.selectDead(Math.min(Math.max(limit, 1), 100));
    }

    @Transactional
    public boolean manualRetryDead(long eventId) {
        return mapper.retryDead(eventId, LocalDateTime.now()) == 1;
    }
}
