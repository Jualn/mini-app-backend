package cn.jualn.miniapp.module.person.service.roster;

import cn.jualn.miniapp.module.person.service.support.PersonnelValues;

import cn.jualn.miniapp.common.constant.RedisKeyConstant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import static cn.jualn.miniapp.module.person.service.support.PersonnelValues.problem;
import static cn.jualn.miniapp.module.person.service.support.PersonnelValues.unavailable;

/** Strict correctness store, independent from the best-effort derived cache. No match inputs in keys. */
@Component @RequiredArgsConstructor
public class RosterApplicationThrottle {
    private final StringRedisTemplate redis;
    private static final DefaultRedisScript<Long> SCRIPT=new DefaultRedisScript<>("local n=redis.call('INCR',KEYS[1]); if n==1 then redis.call('EXPIRE',KEYS[1],60) end; return n",Long.class);
    public void require(long user) {
        final Long count;
        try {count=redis.execute(SCRIPT,List.of(RedisKeyConstant.PARTY_APPLICATION_RATE_PREFIX+user));}
        catch(org.springframework.data.redis.RedisConnectionFailureException e) {throw unavailable();}
        catch(org.springframework.dao.DataAccessException e) {throw unavailable();}
        if(count==null) throw unavailable();if(count>10) throw problem(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,"rate-limited");
    }
}
