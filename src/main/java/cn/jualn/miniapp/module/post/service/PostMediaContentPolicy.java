package cn.jualn.miniapp.module.post.service;

import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.module.media.service.MediaContentPolicy;
import cn.jualn.miniapp.module.post.mapper.PostMapper;
import cn.jualn.miniapp.module.authorization.service.CurrentAuthorization;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service @RequiredArgsConstructor
public class PostMediaContentPolicy implements MediaContentPolicy {
    private final PostMapper posts;
    private final PostPublicAccess access;
    private final CurrentAuthorization authorization;
    @org.springframework.beans.factory.annotation.Autowired private org.springframework.beans.factory.ObjectProvider<GrowthRecordAccess> growth;
    public TargetType targetType(){return TargetType.POST;}
    public boolean mayRead(long id,String key,Long actor) {
        var p=posts.includingDeleted(id);
        if(p==null || p.getDeletedAt()!=null) return false;
        if(growth.getObject().link(id)!=null)return growth.getObject().readable(p,actor,false);
        return access.isPubliclyVisible(id) || actor!=null && actor.equals(p.getUserId());
    }
    public boolean mayReadAdmin(long id,String key,long operator) {
        var p=posts.includingDeleted(id);if(p==null)return false;
        if(growth.getObject().link(id)!=null)return growth.getObject().allowedAdmin(id,operator,"POST_MODERATE","content:read") || growth.getObject().allowedAdmin(id,operator,"POST_SAFETY_REVIEW","review:read") || growth.getObject().allowedAdmin(id,operator,"REPORT_HANDLE","report:read");
        var d=authorization.current(operator);
        return d.source()==cn.jualn.miniapp.module.authorization.model.RbacModels.Source.LEGACY && d.accountEligible() && (d.legacyPermissions().contains("*") || d.legacyPermissions().contains("content:read") || d.legacyPermissions().contains("review:read")) || d.allows("content:read","POST") || d.allowsWork("POST_SAFETY_REVIEW","POST","review:read");
    }
}
