package cn.jualn.miniapp.module.user.service;

import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.module.user.mapper.UserProfileMapper;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Current User login semantics for local business transactions. The caller obtains its authority
 * gate and ordered User/RBAC owner locks first. No cache, new transaction, or expiry normalization. */
@Service
@RequiredArgsConstructor
public class UserCurrentLoginAccess {
    private final UserProfileMapper users;

    @Transactional(propagation=Propagation.MANDATORY,noRollbackFor=BusinessException.class)
    public void require(long user) {
        var row=users.selectCurrentLoginForShare(user);
        if(row==null) throw new BusinessException(ResultCode.USER_NOT_FOUND);
        if(row.getStatus()==null || !Set.of(1,2,3).contains(row.getStatus()))
            throw new BusinessException(ResultCode.INFRASTRUCTURE_ERROR,"Current account status cannot be verified");
        // User restriction timestamps use Beijing business time; this database clock explicitly returns UTC.
        var utc=users.selectCurrentUtcTime();
        if(utc==null) throw new BusinessException(ResultCode.INFRASTRUCTURE_ERROR,"Current account time cannot be verified");
        var now=utc.atOffset(ZoneOffset.UTC).atZoneSameInstant(ZoneId.of("Asia/Shanghai")).toLocalDateTime();
        if(row.getStatus()==3 && (row.getBanExpireAt()==null || row.getBanExpireAt().isAfter(now)))
            throw new BusinessException(ResultCode.USER_BANNED);
    }
}
