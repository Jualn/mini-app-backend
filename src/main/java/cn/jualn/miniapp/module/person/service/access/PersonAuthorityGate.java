package cn.jualn.miniapp.module.person.service.access;

import cn.jualn.miniapp.module.person.mapper.PersonMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Person owns the common gate and its epoch; Authorization owns the subsequent account locks. */
@Service
@RequiredArgsConstructor
@Transactional(propagation=Propagation.MANDATORY)
public class PersonAuthorityGate {
    public enum Access { SHARED, EXCLUSIVE }
    private final PersonMapper mapper;

    public long acquire(Access access) {
        if(access==null) throw cn.jualn.miniapp.module.person.PersonProblems.unavailable();
        return access==Access.EXCLUSIVE?mapper.gateExclusive():mapper.gateShared();
    }
}
