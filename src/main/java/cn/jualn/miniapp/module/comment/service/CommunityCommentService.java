package cn.jualn.miniapp.module.comment.service;

import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.*;
import cn.jualn.miniapp.module.comment.bo.CommentCreateBO;
import cn.jualn.miniapp.module.comment.bo.CommunityCommentBO.*;
import cn.jualn.miniapp.module.comment.entity.Comment;
import cn.jualn.miniapp.module.comment.mapper.CommunityCommentMapper;
import cn.jualn.miniapp.module.post.bo.PostCommunityBO.Page;
import cn.jualn.miniapp.module.post.bo.PostWritePermission;
import cn.jualn.miniapp.module.post.service.*;
import cn.jualn.miniapp.module.user.service.UserService;
import cn.jualn.miniapp.module.media.service.MediaService;
import cn.jualn.miniapp.module.interact.service.InteractionCounts;
import cn.jualn.miniapp.module.interact.service.InteractionLikeQueries;
import cn.jualn.miniapp.module.article.service.ArticleCursor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

@Service @RequiredArgsConstructor
public class CommunityCommentService {
    @org.springframework.beans.factory.annotation.Autowired private org.springframework.beans.factory.ObjectProvider<MentorAnswers> mentorAnswers;
    private final CommunityCommentMapper comments;
    private final PostPublicAccess posts;
    private final PostCommunityService community;
    private final CommentService writer;
    private final MediaService media;
    private final UserService users;
    private final InteractionCounts counts;
    private final InteractionLikeQueries likes;
    private final PlatformTransactionManager transactions;
    private final cn.jualn.miniapp.module.audit.service.CommunitySafetyStates safetyStates;

    public Submission create(CommentCreateBO command,String key) {
        long actor=PostCommunityService.actor(); PostCommunityService.validateKey(key);
        String hash=community.fingerprint(command);
        var old=key==null?null:community.creationReceipt("COMMENT",key);
        if(old!=null) {
            if(!hash.equals(old.fingerprint())) throw cn.jualn.miniapp.common.exception.ContractProblemException.conflict("idempotency-key-reused","Key already used");
            return submission(owned(old.targetId()));
        }
        if(command.getImageObjectKey()!=null && !command.getImageObjectKey().isBlank()) command.setImageObjectKey(media.prepareOwnedImage(TargetType.COMMENT,command.getImageObjectKey()));
        var tx=new TransactionTemplate(transactions);tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        return tx.execute(status -> submission(owned(community.intent("COMMENT",key,hash,()->writer.createComment(command)))));
    }
    public Submission receipt(String key) { PostCommunityService.validateKey(key); var r=community.creationReceipt("COMMENT",key); if(r==null || r.targetId()==null)throw missing(); return submission(owned(r.targetId())); }
    private Comment owned(Long id) { return ownedComment(id); }
    public Mine mine(long id) { return mineValue(ownedComment(id)); }
    private Comment ownedComment(Long id) { long actor=PostCommunityService.actor(); var c=id==null?null:comments.get(id); if(c==null || !Objects.equals(c.getUserId(),actor) || c.getTargetType()!=1 || c.getStatus()==3 || !normal(c.getTargetId()))throw missing(); if(growth.link(c.getTargetId())!=null && !growth.mayRead(c.getTargetId(),actor,true))throw missing();return c; }
    @org.springframework.beans.factory.annotation.Autowired private cn.jualn.miniapp.module.post.service.GrowthRecordAccess growth;
    private boolean normal(long post) { return community.isNormal(post); }
    public Page<Mine> mine(String cursor,int size) {return mine(cursor,size,"NORMAL");}
    public Page<Mine> mine(String cursor,int size,String postType) {
        PostCommunityService.typeFilter(postType);
        PostCommunityService.size(size);long actor=PostCommunityService.actor();String context="my-comments/"+actor+"/"+postType;
        String pos=ArticleCursor.decode(cursor,context);Long after=parseId(pos);
        var rows=comments.mine(actor,after,size+1,postType);boolean more=rows.size()>size;var page=more?rows.subList(0,size):rows;
        return new Page<>(page.stream().map(this::mineValue).toList(),more,more?ArticleCursor.encode(page.get(page.size()-1).getId().toString(),context):null);
    }
    private Mine mineValue(Comment c) {return new Mine(submission(c),Objects.toString(c.getContent(),""),c.getImageUrl(),kind(c),formal(c)?mentorAnswers.getObject().attribution(c.getId()):null);}
    private Submission submission(Comment c) {
        String safety=safetyStates.current(4,c.getId(),null,c.getAuditStatus()==1?"PASSED":c.getAuditStatus()==2?"REJECTED":"CHECKING");
        boolean visible=visible(c);
        var actions=new ArrayList<String>();if(c.getStatus()!=3)actions.add("DELETE");if(visible)actions.add("VIEW_CONTEXT");
        return new Submission(c.getId(),c.getTargetId(),c.getParentId(),PostCommunityService.offset(c.getCreatedAt()),visible?"PUBLIC":"PRIVATE",safety,actions,
                "MENTOR_QUALIFICATION_CHANGED".equals(c.getSubmissionNotice())?new cn.jualn.miniapp.module.post.bo.PostCommunityBO.Notice("CHECK_REJECTED","原导师资格或绑定已变更，该回答未能首次公开。",PostCommunityService.offset(c.getCreatedAt())):"QUESTION_CHANGED".equals(c.getSubmissionNotice())?new cn.jualn.miniapp.module.post.bo.PostCommunityBO.Notice("QUESTION_CHANGED","问题内容已变更，请读取新版本后重新回答。",PostCommunityService.offset(c.getCreatedAt())):PostCommunityService.notice(safety,c.getStatus()==4,PostCommunityService.offset(c.getCreatedAt())));
    }
    public PageResult<Root> roots(long post,Long after,int size) {
        requireNormal(post);PostCommunityService.size(size);var rows=comments.roots(post,after,size+1);boolean more=rows.size()>size;var page=more?rows.subList(0,size):rows;
        return PageResult.of(page.stream().map(this::root).toList(),more,more?page.get(page.size()-1).getId():null);
    }
    public PageResult<Reply> replies(long post,long root,Long after,int size,String cursor) {
        requireNormal(post);PostCommunityService.size(size);var p=comments.get(root);if(p==null || p.getParentId()!=null || p.getTargetType()!=1 || !Objects.equals(p.getTargetId(),post) || (!visible(p) && comments.replyCount(post,root)==0))throw missing();
        if(cursor!=null) { if(after!=null)throw ArticleCursor.invalid();after=parseId(ArticleCursor.decode(cursor,"thread/"+post+"/"+root)); }
        var rows=comments.replies(post,root,after,size+1);boolean more=rows.size()>size;var page=more?rows.subList(0,size):rows;
        return PageResult.of(page.stream().map(this::reply).toList(),more,more?page.get(page.size()-1).getId():null);
    }
    private Root root(Comment c) {
        int count=Math.toIntExact(comments.replyCount(c.getTargetId(),c.getId()));
        var preview=comments.replies(c.getTargetId(),c.getId(),null,2).stream().map(this::reply).toList();
        if(c.getDeletedAt()!=null || c.getStatus()!=1) return new PlaceholderRoot(c.getId(),c.getStatus()==3?"AUTHOR_DELETED":"ADMIN_HIDDEN",c.getCreatedAt(),count,preview,PostWritePermission.denied("NOT_AUTHORIZED"));
        return new VisibleRoot(c.getId(),Objects.toString(c.getContent(),""),c.getImageUrl(),users.getSimpleInfo(c.getUserId()),c.getCreatedAt(),
                Math.toIntExact(counts.getLikeCount(TargetType.COMMENT,c.getId())),likes.isLiked(TargetType.COMMENT,c.getId()),count,preview,community.writePermission(),"VISIBLE",kind(c),formal(c)?mentorAnswers.getObject().attribution(c.getId()):null);
    }
    private Reply reply(Comment c) {
        var target=comments.get(c.getReplyToCommentId()==null?c.getParentId():c.getReplyToCommentId());
        var replyTo=target!=null && target.getStatus()==1 && target.getDeletedAt()==null?users.getSimpleInfo(target.getUserId()):null;
        return new Reply(c.getId(),c.getParentId(),Objects.toString(c.getContent(),""),users.getSimpleInfo(c.getUserId()),replyTo,c.getReplyToCommentId(),c.getCreatedAt(),
                Math.toIntExact(counts.getLikeCount(TargetType.COMMENT,c.getId())),likes.isLiked(TargetType.COMMENT,c.getId()),community.writePermission(),kind(c));
    }
    public Context context(long post,long id) {
        requireNormal(post);var c=comments.get(id);
        if(c==null || c.getTargetType()!=1 || !Objects.equals(c.getTargetId(),post) || !visible(c))return new Context(post,"UNAVAILABLE",null,null,null);
        var root=c.getParentId()==null?c:comments.get(c.getParentId());if(root==null)return new Context(post,"UNAVAILABLE",null,null,null);
        return new Context(post,"AVAILABLE",root(root),c.getParentId()==null?null:reply(c),c.getParentId()==null?null:ArticleCursor.encode(c.getId().toString(),"thread/"+post+"/"+root.getId()));
    }
    private boolean formal(Comment c) {return c.getParentId()==null && posts.isQa(c.getTargetId());}
    private String kind(Comment c) {return posts.isQa(c.getTargetId())?(c.getParentId()==null?"MENTOR_ANSWER":"DISCUSSION"):null;}
    private boolean visible(Comment c) { if(c.getDeletedAt()!=null || c.getStatus()!=1)return false;try {posts.require(c.getTargetId(),false);}catch(BusinessException failure){return false;}if(c.getParentId()==null)return true;var p=comments.get(c.getParentId());return p!=null && (p.getStatus()==1 && p.getDeletedAt()==null || Set.of(3,4).contains(p.getStatus()) && Boolean.TRUE.equals(c.getCommunityThread())); }
    private void requireNormal(long id) {if(!java.util.Set.of("NORMAL","MENTOR_QA").contains(posts.require(id,false).postType()))throw missing();}
    private static Long parseId(String value) {if(value==null)return null;try {long id=Long.parseLong(value);if(id>0)return id;}catch(NumberFormatException ignored){}throw ArticleCursor.invalid();}
    private static BusinessException missing() {return new BusinessException(ResultCode.COMMENT_NOT_FOUND);}
}
