package cn.jualn.miniapp.module.post;

import cn.jualn.miniapp.module.post.service.*;
import cn.jualn.miniapp.module.post.mapper.PostMapper;
import cn.jualn.miniapp.module.post.entity.Post;
import cn.jualn.miniapp.module.comment.service.*;
import cn.jualn.miniapp.module.comment.mapper.CommunityCommentMapper;
import cn.jualn.miniapp.module.comment.entity.Comment;
import cn.jualn.miniapp.module.authorization.service.CurrentAuthorization;
import cn.jualn.miniapp.module.authorization.model.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PostMediaContentPolicyTest {
    org.springframework.beans.factory.ObjectProvider<GrowthRecordAccess> provider(){var factory=new org.springframework.beans.factory.support.DefaultListableBeanFactory();factory.registerSingleton("growth",mock(GrowthRecordAccess.class));return factory.getBeanProvider(GrowthRecordAccess.class);}
    CurrentAuthorization authorization=mock(CurrentAuthorization.class);
    void decision(String space,String permission,String duty,String target) {
        var grant=new RbacModels.Grant("synthetic","role",space,"GLOBAL",List.of(permission),null,duty,new UnifiedModels.Target(target,null,null));
        when(authorization.current(9)).thenReturn(new RbacModels.Decision(9,RbacModels.Source.ASSIGNMENT,"v","c",java.time.LocalDateTime.now(),true,List.of(),List.of(grant)));
    }
    @Test void postPreviewNeedsActualPostContentOrPlatformReviewScope() {
        var rows=mock(PostMapper.class);when(rows.includingDeleted(1)).thenReturn(new Post());var policy=new PostMediaContentPolicy(rows,mock(PostPublicAccess.class),authorization);
        org.springframework.test.util.ReflectionTestUtils.setField(policy,"growth",provider());
        decision("ACTIVITY","content:read","ACTIVITY_CONTENT","PLATFORM");assertFalse(policy.mayReadAdmin(1,"private/a",9));
        decision("POST","review:read","POST_SAFETY_REVIEW","PERSON");assertFalse(policy.mayReadAdmin(1,"private/a",9));
        decision("POST","review:read","ACTIVITY_SAFETY_REVIEW","PLATFORM");assertFalse(policy.mayReadAdmin(1,"private/a",9));
        decision("POST","review:read","POST_SAFETY_REVIEW","PLATFORM");assertTrue(policy.mayReadAdmin(1,"private/a",9));
        decision("POST","content:read","POST_CONTENT","PLATFORM");assertTrue(policy.mayReadAdmin(1,"private/a",9));
    }
    @Test void commentPreviewUsesItsRealSubjectAndExactImageReference() {
        var rows=mock(CommunityCommentMapper.class);var comment=new Comment();comment.setTargetType(1);comment.setTargetId(1L);comment.setImageObjectKey("private/a");when(rows.get(1)).thenReturn(comment);
        var policy=new CommentMediaContentPolicy(rows,mock(CommentTargetAccess.class),authorization);
        org.springframework.test.util.ReflectionTestUtils.setField(policy,"growth",mock(GrowthRecordAccess.class));
        decision("ACTIVITY","content:read","ACTIVITY_CONTENT","PLATFORM");assertFalse(policy.mayReadAdmin(1,"private/a",9));
        decision("POST","review:read","POST_SAFETY_REVIEW","PLATFORM");assertTrue(policy.mayReadAdmin(1,"private/a",9));assertFalse(policy.mayReadAdmin(1,"private/b",9));
    }
}
