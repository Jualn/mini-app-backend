package cn.jualn.miniapp.module.user.service;

import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.module.user.mapper.UserProfileMapper;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** User-owned account eligibility. Does not infer organizations, political identities or roles. */
@Service
public class UserLearningTaskRecipientService {
    private final UserProfileMapper users;
    public UserLearningTaskRecipientService(UserProfileMapper users) { this.users=users; }

    @Transactional(propagation=Propagation.MANDATORY)
    public List<Long> directory() {return users.selectLearningTaskEligibleDirectory();}

    @Transactional(propagation=Propagation.MANDATORY)
    public List<Long> available(List<Long> ids) {return ids.isEmpty()?List.of():users.selectLearningTaskEligibleForShare(ids.stream().distinct().sorted().toList());}

    @Transactional(propagation=Propagation.MANDATORY)
    public List<Long> requireEligible(List<Long> ids) {
        List<Long> expected=ids.stream().distinct().sorted().toList();
        if(expected.isEmpty()) { return expected; }
        List<Long> eligible=users.selectLearningTaskEligibleForShare(expected);
        if(!expected.equals(eligible)) { throw new BusinessException(ResultCode.FORBIDDEN); }
        return eligible;
    }
}
