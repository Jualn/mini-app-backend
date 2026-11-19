package cn.jualn.miniapp.module.post.service;

import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.module.audit.enums.AuditStatus;
import cn.jualn.miniapp.module.post.bo.PostCommentAccessBO;
import cn.jualn.miniapp.module.post.entity.Post;
import cn.jualn.miniapp.module.post.enums.PostStatus;
import cn.jualn.miniapp.module.post.mapper.PostMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import java.time.LocalDateTime;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Post owner 的公开资格；不依赖互动编排，避免 Post → Interact → Post 循环。
 */
@Service
@RequiredArgsConstructor
public class PostPublicAccess {
    public static final long PENDING_VISIBLE_MINUTES = 10;
    private final PostMapper posts;
    @org.springframework.beans.factory.annotation.Autowired private org.springframework.beans.factory.ObjectProvider<GrowthRecordAccess> growth;

    public PostCommentAccessBO require(Long id, boolean mutation) {
        if (id == null) throw new BusinessException(ResultCode.POST_PARAM_INVALID);
        boolean growing=growth.getObject().link(id)!=null;
        Long actor=cn.jualn.miniapp.common.constant.UserContext.getUserId();
        if(growing && mutation){if(actor==null)throw new BusinessException(ResultCode.POST_NOT_FOUND);growth.getObject().guard(actor);}
        Post post = mutation ? posts.lockPost(id) : posts.selectById(id);
        if(post!=null && growing) {
            if(!growth.getObject().readable(post,actor,true)) throw new BusinessException(ResultCode.POST_NOT_FOUND);
            if(mutation && org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive())org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization(){@Override public void beforeCommit(boolean readOnly){if(!growth.getObject().mayRead(id,actor,true))throw new BusinessException(ResultCode.POST_NOT_FOUND);}});
            return new PostCommentAccessBO(post.getUserId(),post.getPostType());
        }
        if (post == null || !visible(post, LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")))) {
            throw new BusinessException(ResultCode.POST_NOT_FOUND);
        }
        return new PostCommentAccessBO(post.getUserId(), post.getPostType());
    }

    public boolean isPubliclyVisible(long id) { var p=posts.selectById(id);return p!=null && growth.getObject().link(id)==null && visible(p,LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"))); }
    public boolean isNormal(long id) { var p=posts.includingDeleted(id); return p!=null && java.util.Set.of("NORMAL","MENTOR_QA").contains(p.getPostType()); }
    public boolean isQa(long id) {var p=posts.includingDeleted(id);return p!=null && "MENTOR_QA".equals(p.getPostType());}
    public boolean isGrowth(long id){return growth.getObject().link(id)!=null;}
    public String questionVersion(long id) {var p=posts.selectById(id);return p==null?null:p.getQuestionVersion();}
    public void lockForTransition(long id) { posts.lockPost(id); }
    public static boolean visible(Post post, LocalDateTime now) {
        if (post.getDeletedAt()!=null || Boolean.TRUE.equals(post.getAdminHidden()) || !Integer.valueOf(2).equals(post.getStatus())) return false;
        if (Integer.valueOf(1).equals(post.getAuditStatus())) return true;
        return Integer.valueOf(0).equals(post.getAuditStatus()) && Boolean.TRUE.equals(post.getCommunityVersion())
                && post.getTemporaryVisibleUntil()!=null && now.isBefore(post.getTemporaryVisibleUntil());
    }
    public static void publicAuditCondition(LambdaQueryWrapper<Post> query) {
        excludeGrowth(query);
        LocalDateTime now=LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"));
        query.eq(Post::getAdminHidden,false).and(w -> w.eq(Post::getAuditStatus,1)
                .or(p -> p.eq(Post::getAuditStatus,0).eq(Post::getCommunityVersion,true).gt(Post::getTemporaryVisibleUntil,now)));
    }
    public static void excludeGrowth(LambdaQueryWrapper<Post> query) {query.apply("NOT EXISTS (SELECT 1 FROM post_growth_record growth WHERE growth.post_id=post.id)");}
}
