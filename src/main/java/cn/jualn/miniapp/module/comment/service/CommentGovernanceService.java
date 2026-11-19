package cn.jualn.miniapp.module.comment.service;

import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.module.admin.operation.service.AdminAuditJournal;
import cn.jualn.miniapp.module.audit.service.ContentSafetyAuthorization;
import cn.jualn.miniapp.module.audit.service.ContentSafetyInvalidation;
import cn.jualn.miniapp.module.authorization.service.CurrentAuthorization;
import cn.jualn.miniapp.module.comment.bo.AdminCommentActionBO;
import cn.jualn.miniapp.module.comment.entity.Comment;
import cn.jualn.miniapp.module.comment.enums.CommentStatus;
import cn.jualn.miniapp.module.comment.mapper.CommentMapper;
import cn.jualn.miniapp.module.comment.mapper.AdminCommentStateRow;
import cn.jualn.miniapp.module.comment.service.impl.CommentAuditCallback;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class CommentGovernanceService {
    @org.springframework.beans.factory.annotation.Autowired private org.springframework.beans.factory.ObjectProvider<MentorAnswers> mentorAnswers;
    private final CurrentAuthorization authorization;
    @org.springframework.beans.factory.annotation.Autowired private cn.jualn.miniapp.module.post.service.GrowthRecordAccess growth;
    private final CommentMapper commentMapper;
    private final CommentCounters counters;
    private final AdminAuditJournal managementAudit;
    private final CommentTargetAccess targets;
    private final cn.jualn.miniapp.module.comment.mapper.CommunityCommentMapper community;
    private final cn.jualn.miniapp.module.post.service.PostCommunityNotifications notices;
    private final ObjectProvider<ContentSafetyInvalidation> safetyReviews;
    private final ObjectProvider<CommentAuditCallback> safetyCallback;

    public java.util.List<cn.jualn.miniapp.module.post.bo.PostCommunityBO.Management> managementHistory(long id) {
        if(!targets.isNormalPostComment(id))return java.util.List.of();
        requireGrowthOrPlatform(id,cn.jualn.miniapp.common.security.AdminStpUtil.STP_LOGIC.getLoginIdAsLong(),"content:read");return community.historyFor(id);
    }
    private void requireReview(long id,long actor) {
        var c=community.get(id);
        if(c!=null && c.getTargetType()==1 && growth.link(c.getTargetId())!=null)growth.requireAdmin(c.getTargetId(),actor,"POST_SAFETY_REVIEW","review:read","review:decide");
        else authorization.requireWork(actor,ContentSafetyAuthorization.duty(managementNamespace(id)),managementNamespace(id),"review:read","review:decide");
    }
    private void requireGrowthOrPlatform(long comment,long actor,String permission) {
        var c=community.get(comment);
        if(c!=null && c.getTargetType()==1 && growth.link(c.getTargetId())!=null)growth.requireAdmin(c.getTargetId(),actor,"POST_MODERATE","content:read",permission);
        else authorization.require(actor,"content:manage",managementNamespace(comment),permission);
    }
    public boolean canRestore(long id) {
        var c=community.get(id);if(c==null)return false;
        if(targets.isQaPostComment(id) && c.getParentId()==null && !mentorAnswers.getObject().restorable(id))return false;
        if(!targets.isNormalPostComment(id))return c.getStatus()==4 && c.getAuditStatus()==1;
        if(c.getStatus()!=4 || c.getAuditStatus()!=1 || community.historyFor(id).stream().noneMatch(h->"TAKE_DOWN".equals(h.action())))return false;
        if(c.getTargetType()==1 && growth.link(c.getTargetId())!=null)return growth.safeVersion(c.getTargetId());
        try {targets.requireSubject(cn.jualn.miniapp.common.enums.TargetType.POST,c.getTargetId(),false);return true;}
        catch(BusinessException unavailable){if(unavailable.getResultCode()==ResultCode.POST_NOT_FOUND)return false;throw unavailable;}
    }
    public void lockForReport(long id) { targets.lockForRemoval(id); }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public void takeDownComment(AdminCommentActionBO command) {
        requireGrowthOrPlatform(command.getCommentId(),command.getOperatorId(),"comment:dispose");

        validateAdminAction(command);
        targets.lockForRemoval(command.getCommentId());
        AdminCommentStateRow state = commentMapper.selectAdminStateById(command.getCommentId());
        if (state == null) {
            throw new BusinessException(ResultCode.COMMENT_NOT_FOUND);
        }
        int updated = commentMapper.update(null,
                new LambdaUpdateWrapper<Comment>()
                        .set(Comment::getStatus, CommentStatus.ADMIN_DELETED.getCode())
                        .set(Comment::getDeletedAt, LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")))
                        .eq(Comment::getId, command.getCommentId())
                        .eq(Comment::getStatus, CommentStatus.NORMAL.getCode())
                        .isNull(Comment::getDeletedAt));
        if (updated == 0) {
            throw new BusinessException(ResultCode.DATA_CONFLICT, "评论当前状态不允许下架");
        }
        counters.decrease(state.getTargetType(), state.getTargetId(), state.getParentId());
        if(targets.isNormalPostComment(command.getCommentId())) {var c=community.get(command.getCommentId());community.history(c.getId(),command.getOperatorId(),"TAKE_DOWN",command.getReason());notices.handled(c.getUserId(),c.getId(),0,"hide:"+community.historyFor(c.getId()).get(0).id(),true);}
        safetyReviews.getObject().invalidateTarget(4, command.getCommentId(), "target-unpublished");
        managementAudit.operation(java.util.UUID.randomUUID().toString(), command.getOperatorId(), "actOnAdminManagedContent", "comment", String.valueOf(command.getCommentId()), java.time.LocalDateTime.now(java.time.ZoneOffset.UTC));
        log.info("[CommentService.takeDownComment][完成] operatorId={}, commentId={}",
                command.getOperatorId(), command.getCommentId());
    }

    /**
     * 恢复评论
     *
     * @param command
     */
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public void restoreComment(AdminCommentActionBO command) {
        requireGrowthOrPlatform(command.getCommentId(),command.getOperatorId(),"comment:dispose");

        validateAdminAction(command);
        targets.lockForRemoval(command.getCommentId());
        AdminCommentStateRow state = commentMapper.selectAdminStateById(command.getCommentId());
        if (state == null) {
            throw new BusinessException(ResultCode.COMMENT_NOT_FOUND);
        }
        if(targets.isNormalPostComment(command.getCommentId()) && community.historyFor(command.getCommentId()).stream().noneMatch(h->"TAKE_DOWN".equals(h.action()))) throw cn.jualn.miniapp.common.exception.ContractProblemException.conflict("post-legacy-unresolved","No proven hide source");
        if(targets.isNormalPostComment(command.getCommentId()) && state.getParentId()!=null) {
            var c=community.get(command.getCommentId());var root=community.get(state.getParentId());
            if(root==null || (root.getStatus()!=1 && !Boolean.TRUE.equals(c.getCommunityThread()))) throw cn.jualn.miniapp.common.exception.ContractProblemException.conflict("post-legacy-unresolved","Reply exposure requires proven community scope");
        }
        if(state.getTargetType()==1) targets.requireSubject(cn.jualn.miniapp.common.enums.TargetType.POST,state.getTargetId(),true);
        if(targets.isQaPostComment(command.getCommentId()) && state.getParentId()==null && !mentorAnswers.getObject().restorable(command.getCommentId())) throw cn.jualn.miniapp.common.exception.ContractProblemException.conflict("post-legacy-unresolved","Original published answer evidence is required");
        if (commentMapper.restoreAdminComment(command.getCommentId()) == 0) {
            throw new BusinessException(ResultCode.DATA_CONFLICT,
                    "只有审核通过且由管理员下架的评论可以恢复");
        }
        counters.increase(state.getTargetType(), state.getTargetId(), state.getParentId());
        if(targets.isNormalPostComment(command.getCommentId())) {var c=community.get(command.getCommentId());community.history(c.getId(),command.getOperatorId(),"RESTORE",command.getReason());notices.handled(c.getUserId(),c.getId(),0,"restore:"+community.historyFor(c.getId()).get(0).id(),true);}
        managementAudit.operation(java.util.UUID.randomUUID().toString(), command.getOperatorId(), "actOnAdminManagedContent", "comment", String.valueOf(command.getCommentId()), java.time.LocalDateTime.now(java.time.ZoneOffset.UTC));
        log.info("[CommentService.restoreComment][完成] operatorId={}, commentId={}",
                command.getOperatorId(), command.getCommentId());
    }

    /**
     * 审核评论
     *
     * @param commentId
     * @param operatorId
     * @param remark
     */
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public void approveCommentReview(Long commentId, Long operatorId, String remark) {
        mentorAnswers.getObject().guardPublication(commentId,operatorId);
        requireReview(commentId,operatorId);

        validateReviewAction(commentId, operatorId, false, remark);
        AdminCommentStateRow state = commentMapper.selectAdminStateById(commentId);
        if (state == null) {
            throw new BusinessException(ResultCode.COMMENT_NOT_FOUND);
        }
        applyContentSafety(commentId, "approved");
        log.info("[CommentService.approveCommentReview][完成] operatorId={}, commentId={}", operatorId, commentId);
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public void rejectCommentReview(Long commentId, Long operatorId, String reason) {
        requireReview(commentId,operatorId);

        validateReviewAction(commentId, operatorId, true, reason);
        if (commentMapper.rejectAdminReview(commentId) != 1) {
            AdminCommentStateRow state = commentMapper.selectAdminStateById(commentId);
            if (state == null) {
                throw new BusinessException(ResultCode.COMMENT_NOT_FOUND);
            }
            throw new BusinessException(ResultCode.DATA_CONFLICT, "评论当前状态不允许拒绝复核");
        }
        if(targets.isNormalPostComment(commentId)) {var rejected=community.get(commentId);if(rejected!=null)notices.handled(rejected.getUserId(),commentId,0,"rejected",true);}
        log.info("[CommentService.rejectCommentReview][完成] operatorId={}, commentId={}", operatorId, commentId);
    }

    @Transactional(rollbackFor = Exception.class)
    public void applyContentSafety(Long commentId, String state) {
        if ("approved".equals(state)) {
            var current = commentMapper.selectById(commentId);
            if (current == null || current.getStatus() != 0 || current.getAuditStatus() != 0)
                throw new BusinessException(ResultCode.DATA_CONFLICT, "审核目标已变化");
            safetyCallback.getObject().activateCommentAfterAuditPass(commentId);
            return;
        }
        if("pending".equals(state) || "check-failed".equals(state)) {
            if(targets.isNormalPostComment(commentId)) {var c=community.get(commentId);if(c!=null && c.getStatus()==0)notices.handled(c.getUserId(),commentId,0,state,true);}
            return;
        }
        if (commentMapper.rejectAdminReview(commentId) != 1)
            throw new BusinessException(ResultCode.DATA_CONFLICT, "审核目标已变化");
        if(targets.isNormalPostComment(commentId)) {var c=community.get(commentId);if(c!=null)notices.handled(c.getUserId(),commentId,0,state,true);}
    }

    private void validateReviewAction(Long commentId, Long operatorId, boolean reasonRequired, String reason) {
        if (commentId == null || operatorId == null) {
            throw new BusinessException(ResultCode.AUDIT_PARAM_INVALID, "人工复核参数不完整");
        }
        if (reasonRequired && !StringUtils.hasText(reason)) {
            throw new BusinessException(ResultCode.AUDIT_PARAM_INVALID, "拒绝原因不能为空");
        }
    }

    private void validateAdminAction(AdminCommentActionBO command) {
        if (command == null || command.getOperatorId() == null || command.getCommentId() == null) {
            throw new BusinessException(ResultCode.INVALID_OPERATION, "管理操作参数不能为空");
        }
        if (!StringUtils.hasText(command.getReason()) || command.getReason().length()>255) {
            throw new BusinessException(ResultCode.INVALID_OPERATION, "操作原因不能为空");
        }
    }

    public String managementNamespace(Long commentId) {
        var state = commentMapper.selectAdminStateById(commentId);
        if (state == null) throw new BusinessException(ResultCode.COMMENT_NOT_FOUND);
        return CommentTargetAccess.namespace(state.getTargetType());
    }
}
