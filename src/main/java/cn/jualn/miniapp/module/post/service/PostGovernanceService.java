package cn.jualn.miniapp.module.post.service;

import org.springframework.beans.factory.annotation.Autowired;

import cn.jualn.miniapp.module.content.service.ReportTargetAuthorization;

import cn.jualn.miniapp.common.constant.RedisKeyConstant;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.module.admin.operation.service.AdminAuditJournal;
import cn.jualn.miniapp.module.audit.service.ContentSafetyInvalidation;
import cn.jualn.miniapp.module.authorization.service.CurrentAuthorization;
import cn.jualn.miniapp.module.post.bo.AdminPostActionBO;
import cn.jualn.miniapp.module.post.entity.Post;
import cn.jualn.miniapp.module.post.enums.PostStatus;
import cn.jualn.miniapp.module.post.mapper.PostMapper;
import cn.jualn.miniapp.module.post.mapper.AdminPostStateRow;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class PostGovernanceService {
    @Autowired private ObjectProvider<ReportTargetAuthorization> reportTargets;
    @Autowired private ObjectProvider<MentorQuestions> mentorQuestions;
    private final CurrentAuthorization authorization;
    @Autowired private GrowthRecordAccess growth;
    private final PostMapper postMapper;
    private final RedisService redisService;
    private final AdminAuditJournal managementAudit;
    private final cn.jualn.miniapp.module.post.mapper.PostCommunityMapper community;
    private final PostCommunityNotifications notices;
    private final ObjectProvider<ContentSafetyInvalidation> safetyReviews;

    public void lockForReport(long id) { postMapper.lockPost(id); }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public void pinPost(AdminPostActionBO command) {
        updatePostFlag(command, true, true);
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public void unpinPost(AdminPostActionBO command) {
        updatePostFlag(command, true, false);
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public void featurePost(AdminPostActionBO command) {
        updatePostFlag(command, false, true);
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public void unfeaturePost(AdminPostActionBO command) {
        updatePostFlag(command, false, false);
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public void takeDownPost(AdminPostActionBO command) {
        requireGrowthOrPlatform(command.getPostId(),command.getOperatorId(),"post:dispose");
        hidePost(command);
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public void takeDownReportedPost(AdminPostActionBO command) {
        reportTargets.getObject().requireDisposal("post",command.getPostId(),command.getOperatorId());
        hidePost(command);
    }

    private void hidePost(AdminPostActionBO command) {
        validateAdminAction(command, true);
        var post=postMapper.lockPost(command.getPostId());
        if(post==null) throw new BusinessException(ResultCode.POST_NOT_FOUND);
        if(Boolean.TRUE.equals(post.getAdminHidden())) throw new BusinessException(ResultCode.DATA_CONFLICT);
        int updated=postMapper.update(null,new LambdaUpdateWrapper<Post>().eq(Post::getId,post.getId())
                .eq(Post::getAdminHidden,false).set(Post::getAdminHidden,true).set(Post::getIsPinned,false).set(Post::getIsFeatured,false));
        if(updated!=1) throw new BusinessException(ResultCode.DATA_CONFLICT);
        community.history(post.getId(),command.getOperatorId(),"TAKE_DOWN",command.getReason().trim(),post.getRevision());
        notices.handled(post.getUserId(),post.getId(),post.getAuditRevision(),"hide:"+community.historyFor(post.getId()).get(0).id(),false);
        afterCommit(() -> redisService.delete(buildPostDetailCacheKey(command.getPostId())));
        managementAudit.operation(java.util.UUID.randomUUID().toString(), command.getOperatorId(), "actOnAdminManagedContent", "post", String.valueOf(command.getPostId()), java.time.LocalDateTime.now(java.time.ZoneOffset.UTC));
        log.info("[PostService.takeDownPost][完成] operatorId={}, postId={}",
                command.getOperatorId(), command.getPostId());
    }

    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public void restorePost(AdminPostActionBO command) {
        validateAdminAction(command,true);
        requireGrowthOrPlatform(command.getPostId(),command.getOperatorId(),"post:dispose");
        var post=postMapper.lockPost(command.getPostId());
        if(post==null) throw cn.jualn.miniapp.common.exception.ContractProblemException.conflict("post-legacy-unresolved","Deleted content has no restorable live version");
        if(!Boolean.TRUE.equals(post.getAdminHidden()) || community.historyFor(post.getId()).stream().noneMatch(h->"TAKE_DOWN".equals(h.action())))
            throw cn.jualn.miniapp.common.exception.ContractProblemException.conflict("post-legacy-unresolved","No proven management hide");
        if("MENTOR_QA".equals(post.getPostType()))mentorQuestions.getObject().summary(post.getId());
        if(post.getAuditStatus()!=1 || post.getStatus()!=2) throw cn.jualn.miniapp.common.exception.ContractProblemException.conflict("post-moderation-conflict","Safety is not passed");
        postMapper.update(null,new LambdaUpdateWrapper<Post>().eq(Post::getId,post.getId()).eq(Post::getAdminHidden,true).set(Post::getAdminHidden,false));
        community.history(post.getId(),command.getOperatorId(),"RESTORE",command.getReason().trim(),post.getRevision());
        notices.handled(post.getUserId(),post.getId(),post.getAuditRevision(),"restore:"+community.historyFor(post.getId()).get(0).id(),false);
        managementAudit.operation(java.util.UUID.randomUUID().toString(),command.getOperatorId(),"actOnAdminManagedContent","post",post.getId().toString(),java.time.LocalDateTime.now(java.time.ZoneOffset.UTC));
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public void approvePostReview(Long postId, Long operatorId, String remark) {
        if(growth.link(postId)!=null)growth.requireAdmin(postId,operatorId,"POST_SAFETY_REVIEW","review:read","review:decide");else authorization.requireWork(operatorId, "POST_SAFETY_REVIEW", "POST", "review:read", "review:decide");

        validateReviewAction(postId, operatorId, false, remark);
        if (postMapper.approveAdminReview(postId) != 1) {
            throwAdminPostConflict(postId, "帖子当前状态不允许通过复核");
        }
        afterCommit(() -> redisService.delete(buildPostDetailCacheKey(postId)));
        log.info("[PostService.approvePostReview][完成] operatorId={}, postId={}", operatorId, postId);
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public void rejectPostReview(Long postId, Long operatorId, String reason) {
        if(growth.link(postId)!=null)growth.requireAdmin(postId,operatorId,"POST_SAFETY_REVIEW","review:read","review:decide");else authorization.requireWork(operatorId, "POST_SAFETY_REVIEW", "POST", "review:read", "review:decide");

        validateReviewAction(postId, operatorId, true, reason);
        if (postMapper.rejectAdminReview(postId, reason.trim()) != 1) {
            throwAdminPostConflict(postId, "帖子当前状态不允许拒绝复核");
        }
        afterCommit(() -> redisService.delete(buildPostDetailCacheKey(postId)));
        var rejected=postMapper.selectById(postId);if(rejected!=null && Boolean.TRUE.equals(rejected.getCommunityVersion()))notices.handled(rejected.getUserId(),postId,rejected.getAuditRevision(),"rejected",false);
        log.info("[PostService.rejectPostReview][完成] operatorId={}, postId={}", operatorId, postId);
    }

    @Transactional(rollbackFor = Exception.class)
    public void applyContentSafety(Long postId, long revision, String state) {
        var condition = new LambdaUpdateWrapper<Post>().eq(Post::getId, postId).eq(Post::getAuditRevision, revision)
                .isNull(Post::getDeletedAt).in(Post::getStatus, 1, 2).eq(Post::getAuditStatus, 0);
        boolean approved = "approved".equals(state), rejected = "rejected".equals(state);
        condition.set(Post::getStatus, approved ? 2 : rejected ? 3 : 1).set(Post::getAuditStatus, approved ? 1 : rejected ? 2 : 0);
        if (postMapper.update(null, condition) != 1)
            throw new BusinessException(ResultCode.DATA_CONFLICT, "审核目标已变化");
        if(!approved) { var p=postMapper.selectById(postId); if(p!=null && Boolean.TRUE.equals(p.getCommunityVersion())) notices.handled(p.getUserId(),postId,p.getAuditRevision(),state,false); }
        afterCommit(() -> redisService.delete(buildPostDetailCacheKey(postId)));
    }

    private void validateReviewAction(Long postId, Long operatorId, boolean reasonRequired, String reason) {
        if (postId == null || operatorId == null) {
            throw new BusinessException(ResultCode.AUDIT_PARAM_INVALID, "人工复核参数不完整");
        }
        if (reasonRequired && !StringUtils.hasText(reason)) {
            throw new BusinessException(ResultCode.AUDIT_PARAM_INVALID, "拒绝原因不能为空");
        }
    }

    private void updatePostFlag(AdminPostActionBO command, boolean pinnedFlag, boolean enabled) {
        requireGrowthOrPlatform(command.getPostId(),command.getOperatorId(),"post:curate");

        validateAdminAction(command, false);
        LambdaUpdateWrapper<Post> update = new LambdaUpdateWrapper<Post>()
                .eq(Post::getId, command.getPostId())
                .eq(Post::getStatus, PostStatus.PUBLISHED.getCode())
                .isNull(Post::getDeletedAt);
        if (pinnedFlag) {
            update.set(Post::getIsPinned, enabled).eq(Post::getIsPinned, !enabled);
        } else {
            update.set(Post::getIsFeatured, enabled).eq(Post::getIsFeatured, !enabled);
        }
        if (postMapper.update(null, update) == 0) {
            throwAdminPostConflict(command.getPostId(), "帖子状态或运营标记已变化");
        }
        afterCommit(() -> redisService.delete(buildPostDetailCacheKey(command.getPostId())));
        managementAudit.operation(java.util.UUID.randomUUID().toString(), command.getOperatorId(), "actOnAdminManagedContent", "post", String.valueOf(command.getPostId()), java.time.LocalDateTime.now(java.time.ZoneOffset.UTC));
        log.info("[PostService.updatePostFlag][完成] operatorId={}, postId={}, flag={}, enabled={}",
                command.getOperatorId(), command.getPostId(), pinnedFlag ? "pinned" : "featured", enabled);
    }

    private void requireGrowthOrPlatform(long post,long actor,String permission) {
        if(growth.link(post)!=null)growth.requireAdmin(post,actor,"POST_MODERATE","content:read",permission);
        else authorization.require(actor,"content:manage","POST",permission);
    }
    private void validateAdminAction(AdminPostActionBO command, boolean reasonRequired) {
        if (command == null || command.getOperatorId() == null || command.getPostId() == null) {
            throw new BusinessException(ResultCode.INVALID_OPERATION, "管理操作参数不能为空");
        }
        if (reasonRequired && (!StringUtils.hasText(command.getReason()) || command.getReason().length()>255)) {
            throw new BusinessException(ResultCode.INVALID_OPERATION, "操作原因不能为空");
        }
    }

    private void throwAdminPostConflict(Long postId, String message) {
        AdminPostStateRow state = postMapper.selectAdminStateById(postId);
        if (state == null) {
            throw new BusinessException(ResultCode.POST_NOT_FOUND);
        }
        throw new BusinessException(ResultCode.DATA_CONFLICT, message);
    }

    private void afterCommit(Runnable task) {
        if (TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    task.run();
                }
            });
        } else {
            task.run();
        }
    }

    private String buildPostDetailCacheKey(Long postId) {
        return RedisKeyConstant.postDetail(postId);
    }
}
