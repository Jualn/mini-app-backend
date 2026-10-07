package cn.jualn.miniapp.infrastructure.async.message;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class EventConsumptionService {
    private final EventConsumptionMapper mapper;

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean beginOnce(String consumerName, String messageId) {
        try {
            return mapper.insert(consumerName, messageId) == 1;
        } catch (DuplicateKeyException duplicate) {
            return false;
        }
    }
}
