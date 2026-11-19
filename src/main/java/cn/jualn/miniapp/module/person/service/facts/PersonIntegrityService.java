package cn.jualn.miniapp.module.person.service.facts;

import org.springframework.transaction.annotation.Isolation;
import cn.jualn.miniapp.module.person.mapper.PersonMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only operator preflight; absent heads are reported as unknown, not silently backfilled. */
@Service
@RequiredArgsConstructor
public class PersonIntegrityService {
    private final PersonMapper mapper;
    public record Coverage(long authorityEpoch,long unknownUsers,long corruptFacts,long corruptReceipts) {
        public boolean trusted() { return corruptFacts==0 && corruptReceipts==0; }
    }
    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public Coverage inspect() { long epoch=mapper.gateShared();return new Coverage(epoch,mapper.unknownUsers(),mapper.corruptFacts(),mapper.corruptSource()); }
}
