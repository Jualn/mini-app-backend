package cn.jualn.miniapp.module.comment.service;

import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.module.media.service.MediaContentPolicy;
import cn.jualn.miniapp.module.comment.mapper.CommunityCommentMapper;
import cn.jualn.miniapp.module.authorization.service.CurrentAuthorization;
import cn.jualn.miniapp.module.audit.service.ContentSafetyAuthorization;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service @RequiredArgsConstructor
public class CommentMediaContentPolicy implements MediaContentPolicy {
    private final CommunityCommentMapper comments;
    private final CommentTargetAccess targets;
    private final CurrentAuthorization authorization;
    public TargetType targetType(){return TargetType.COMMENT;}
    public boolean mayRead(long id,String key,Long actor) {
        var c=comments.get(id);
        if(c==null || !key.equals(c.getImageObjectKey()) || c.getDeletedAt()!=null || c.getStatus()==3)return false;
        if(actor!=null && actor.equals(c.getUserId())) {
            try {targets.requireSubject(TargetType.fromCode(c.getTargetType()),c.getTargetId(),false);return true;}
            catch(cn.jualn.miniapp.common.exception.BusinessException missing){return false;}
        }
        try {targets.requirePublic(id,false);return true;}
        catch(cn.jualn.miniapp.common.exception.BusinessException missing){return false;}
    }
    public boolean mayReadAdmin(long id,String key,long operator) {
        var c=comments.get(id);if(c==null || !key.equals(c.getImageObjectKey()))return false;
        if(c.getTargetType()==1 && growth.link(c.getTargetId())!=null)return growth.allowedAdmin(c.getTargetId(),operator,"POST_MODERATE","content:read") || growth.allowedAdmin(c.getTargetId(),operator,"POST_SAFETY_REVIEW","review:read") || growth.allowedAdmin(c.getTargetId(),operator,"REPORT_HANDLE","report:read");
        String space=CommentTargetAccess.namespace(c.getTargetType());var d=authorization.current(operator);
        return d.source()==cn.jualn.miniapp.module.authorization.model.RbacModels.Source.LEGACY && d.accountEligible() && (d.legacyPermissions().contains("*") || d.legacyPermissions().contains("content:read") || d.legacyPermissions().contains("review:read")) || d.allows("content:read",space) || d.allowsWork(ContentSafetyAuthorization.duty(space),space,"review:read");
    }
    @org.springframework.beans.factory.annotation.Autowired private cn.jualn.miniapp.module.post.service.GrowthRecordAccess growth;
}
