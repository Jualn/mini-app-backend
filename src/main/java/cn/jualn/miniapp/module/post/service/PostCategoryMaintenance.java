package cn.jualn.miniapp.module.post.service;
import cn.jualn.miniapp.common.security.AdminStpUtil;
import cn.jualn.miniapp.module.authorization.service.CurrentAuthorization;
import cn.jualn.miniapp.module.post.PostProblems;
import cn.jualn.miniapp.module.post.bo.*;
import cn.jualn.miniapp.module.post.mapper.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.List;
@Service @RequiredArgsConstructor
public class PostCategoryMaintenance {
    private final CurrentAuthorization authorization;
    private final PostMapper posts;
    private final PostCommunityMapper community;
    private final PostOrganizationService organization;
    private final PostEditingService editing;
    @org.springframework.beans.factory.annotation.Autowired private GrowthRecordAccess growth;
    @org.springframework.beans.factory.annotation.Autowired private GrowthRecordService growthRecords;
    @Transactional(isolation=Isolation.READ_COMMITTED,rollbackFor=Exception.class)
    public PostVersionedBO<?> set(long id,Long category,String tag) {
        long actor=AdminStpUtil.STP_LOGIC.getLoginIdAsLong();
        boolean isGrowth=growth.link(id)!=null;
        if(isGrowth)growth.requireAdmin(id,actor,"POST_MODERATE","content:read","post:curate");else authorization.require(actor,"content:manage","POST","content:read","post:curate");
        var p=posts.lockPost(id);
        if(p==null || !java.util.Set.of("NORMAL","MENTOR_QA").contains(p.getPostType()))throw new cn.jualn.miniapp.common.exception.BusinessException(cn.jualn.miniapp.common.result.ResultCode.POST_NOT_FOUND);
        PostProblems.match(tag,(isGrowth?growthRecords.version(p):editing.version(p)).etag());
        if(Boolean.TRUE.equals(p.getAdminHidden()))throw cn.jualn.miniapp.common.exception.ContractProblemException.conflict("post-moderation-conflict","Content is managed hidden");
        if("MENTOR_QA".equals(p.getPostType()) && category==null)throw PostProblems.invalid("categoryId","REQUIRED","A mentor question requires a category");
        organization.validateReferences(p.getPostType(),category,List.of());
        if(posts.applyEdit(new PostEffectiveEditBO(id,p.getTitle(),p.getContent(),category,p.getRevision(),false))!=1)throw cn.jualn.miniapp.common.exception.ContractProblemException.preconditionFailed();
        community.history(id,actor,"CHANGE_CATEGORY","分类归属调整",p.getRevision());
        p.setCategoryId(category);p.setRevision(p.getRevision()+1);return isGrowth?growthRecords.version(p):editing.version(p);
    }
}
