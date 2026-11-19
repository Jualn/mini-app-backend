package cn.jualn.miniapp.module.post.service;

import cn.jualn.miniapp.module.media.bo.GrowthMediaBO.Scope;
import cn.jualn.miniapp.module.media.service.GrowthMediaAuthorization;
import cn.jualn.miniapp.module.post.mapper.PostMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service @RequiredArgsConstructor
public class PostGrowthMediaAuthorization implements GrowthMediaAuthorization {
    private final GrowthQualification qualification;
    private final GrowthRecordAccess access;
    private final PostMapper posts;
    private final cn.jualn.miniapp.module.user.service.UserAccountService accounts;
    public boolean candidate(Scope scope) {
        try {
        accounts.assertCommunityContentAllowed(scope.user());
        if(!scope.person().equals(qualification.requirePublisher(scope.user(),scope.organization(),scope.type())))return false;
        if(scope.post()!=null){var post=posts.selectById(scope.post());access.original(post,scope.user(),true);return !Boolean.TRUE.equals(post.getAdminHidden());}
        return true;
        } catch(cn.jualn.miniapp.common.exception.ContractProblemException failure){if(failure.getStatus().is4xxClientError())return false;throw failure;}
    }
}
