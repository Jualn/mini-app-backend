package cn.jualn.miniapp.module.post.service;
import cn.jualn.miniapp.module.post.mapper.*;
import cn.jualn.miniapp.module.post.bo.PostCommunityBO.*;
import cn.jualn.miniapp.module.authorization.service.CurrentAuthorization;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.List;
@Service @RequiredArgsConstructor
public class PostManagementQueries {
    private final PostMapper posts;
    private final PostCommunityMapper community;
    private final org.springframework.beans.factory.ObjectProvider<PostEditingService> editing;
    private final CurrentAuthorization authorization;
    public record State(Lifecycle lifecycle,List<Management> history,String editableEtag,boolean restorable,boolean curatable) { }
    public State get(long id,long actor) {
        var p=posts.includingDeleted(id);if(p==null || !java.util.Set.of("NORMAL","MENTOR_QA").contains(p.getPostType()))return null;
        authorization.require(actor,"content:read","POST","content:read");
        var life=PostCommunityService.lifecycle(p,PostCommunityService.now());
        var history=community.historyFor(id);
        var decision=authorization.current(actor);
        boolean legacy=decision.source()==cn.jualn.miniapp.module.authorization.model.RbacModels.Source.LEGACY && (decision.legacyPermissions().contains("*") || decision.legacyPermissions().contains("content:manage"));
        boolean curate=legacy || decision.allowsAll("POST","content:read","post:curate");
        boolean dispose=legacy || decision.allowsAll("POST","content:read","post:dispose");
        return new State(life,history,curate && p.getDeletedAt()==null?editing.getObject().version(p).etag():null,
                dispose && p.getDeletedAt()==null && Boolean.TRUE.equals(p.getAdminHidden()) && p.getAuditStatus()==1 && p.getStatus()==2 && history.stream().anyMatch(h->"TAKE_DOWN".equals(h.action())),curate && p.getDeletedAt()==null && !Boolean.TRUE.equals(p.getAdminHidden()));
    }
}
