package cn.jualn.miniapp.infrastructure.async.outbox;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class OutboxClaimService {
    private final OutboxMapper mapper;

    @Transactional
    public List<OutboxEvent> claim(String owner, int limit) {
        LocalDateTime now = LocalDateTime.now();
        List<OutboxEvent> claimed = new ArrayList<>();
        for (OutboxEvent event : mapper.selectDueForUpdate(now, limit)) {
            if (mapper.claim(event.getId(), owner, now.plusSeconds(120)) == 1) {
                event.setLeaseOwner(owner);
                event.setAttempt(event.getAttempt() + 1);
                claimed.add(event);
            }
        }
        return claimed;
    }
}
