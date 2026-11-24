package cn.jualn.miniapp.module.audit.service;

import cn.jualn.miniapp.module.audit.mapper.ContentSafetyReviewMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Review invalidation joins the domain owner's transaction; it never applies domain effects. */
@Service
@RequiredArgsConstructor
public class ContentSafetyInvalidation {
    private final ContentSafetyReviewMapper mapper;

    @Transactional(propagation = Propagation.MANDATORY)
    public void invalidateTarget(int type, long id, String reason) {
        mapper.invalidateTarget(type, id, reason);
        if (type == 1) mapper.invalidateSubject(1, id);
        if (type == 4) mapper.invalidateRoot(id);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void invalidateSubject(int type, long id) {
        mapper.invalidateSubject(type, id);
    }
}
