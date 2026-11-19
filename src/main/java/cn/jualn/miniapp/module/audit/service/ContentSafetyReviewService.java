package cn.jualn.miniapp.module.audit.service;

import cn.jualn.miniapp.module.audit.enums.AuditScene;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.module.audit.bo.*;
import cn.jualn.miniapp.module.audit.bo.SafetyReviewProjection.*;
import cn.jualn.miniapp.module.audit.entity.*;
import cn.jualn.miniapp.module.audit.mapper.ContentSafetyReviewMapper;
import cn.jualn.miniapp.module.media.service.MediaService;
import cn.jualn.miniapp.module.post.service.PostGovernanceService;
import cn.jualn.miniapp.module.comment.service.CommentGovernanceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.LocalDateTime;
import java.util.*;

/** Content safety only. Domain owners apply effects; log IDs remain stable compatibility anchors. */
@Service @RequiredArgsConstructor
public class ContentSafetyReviewService {
    @org.springframework.beans.factory.annotation.Autowired private ObjectProvider<cn.jualn.miniapp.module.comment.service.MentorAnswers> mentorAnswers;
    private final ContentSafetyReviewMapper mapper;
    @org.springframework.beans.factory.annotation.Autowired private cn.jualn.miniapp.module.content.service.GrowthManagementTargets growth;
    private final ObjectMapper json;
    private final cn.jualn.miniapp.module.content.service.AdminQaContextReader qa;

    private final ObjectProvider<cn.jualn.miniapp.module.media.service.MediaQueries> media;
    private final ObjectProvider<PostGovernanceService> posts;
    private final ObjectProvider<CommentGovernanceService> comments;
    public static boolean supported(AuditScene scene) {return scene==AuditScene.POST || scene==AuditScene.COMMENT;}
    public cn.jualn.miniapp.module.content.bo.AdminPostProjection.QaContext qaContext(ContentSafetyReview round) {
        if(!"POST".equals(round.getResourceNamespace()) || round.getSnapshotQaRelation()==null) return null;
        return qa.safety(decode(round.getSnapshotQaRelation(),cn.jualn.miniapp.module.content.bo.AdminPostProjection.Relation.class));
    }
    public void guardMentorPublication(long task,long reviewer) {var r=mapper.find(task);if(r!=null && r.getTargetType()==4)mentorAnswers.getObject().guardPublication(r.getTargetId(),reviewer);}
    public ContentSafetyReview find(long task) {return mapper.find(task);}
    public ContentSafetyReview forCheck(long check) {return mapper.forCheck(check);}
    public ContentSafetyReview lock(long task) {return mapper.lock(task);}
    private String encode(Object value) {
        try {return json.writeValueAsString(value);} catch(com.fasterxml.jackson.core.JsonProcessingException e) {throw unavailable();}
    }
    private <T> T decode(String value,Class<T> type) {
        try {return json.readValue(value,type);} catch(com.fasterxml.jackson.core.JsonProcessingException e) {throw unavailable();}
    }
    private ContractProblemException unavailable() {return new ContractProblemException(HttpStatus.SERVICE_UNAVAILABLE,"/problems/service-unavailable","Content safety evidence unavailable");}

    @Transactional(propagation=Propagation.MANDATORY)
    public void reserve(AuditReserveBO input,AuditReserveResultBO result) {
        if(!supported(input.getAuditScene()) || !result.hasAuditTask()) return;
        var r=new ContentSafetyReview();r.setTargetType(input.getAuditScene().getCode());r.setTargetId(input.getTargetId());
        r.setSubmissionVersion(UUID.randomUUID().toString());r.setState("checking");
        r.setCreatedAt(LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")));
        var attachments=new ArrayList<AdminReviewDetailBO.Attachment>();
        if(input.getAuditScene()==AuditScene.POST) {
            var p=mapper.lockPost(input.getTargetId());if(p==null) throw unavailable();
            if("MENTOR_QA".equals(p.getPostType())) r.setSnapshotQaRelation(encode(new cn.jualn.miniapp.module.content.bo.AdminPostProjection.Relation(p.getId(),null,null,p.getQuestionVersion())));
            r.setScene("post");r.setResourceNamespace("POST");r.setBusinessRevision(p.getAuditRevision());
            r.setSnapshotTitle(p.getTitle()==null?"":p.getTitle());r.setSnapshotContent(p.getContent());
            for(var a:media.getObject().listAttachments(TargetType.POST,p.getId())) {
                var x=new AdminReviewDetailBO.Attachment();x.setId(a.getId());x.setType(a.getType().name().toLowerCase(Locale.ROOT));x.setName(a.getOriginalName()==null?"附件 "+a.getId():a.getOriginalName());x.setUrl(a.getUrl());attachments.add(x);
            }
        } else {
            var c=mapper.lockComment(input.getTargetId());if(c==null) throw unavailable();
            if(c.getTargetType()==1) {
                var question=mapper.post(c.getTargetId());
                if(question!=null && "MENTOR_QA".equals(question.getPostType()))
                    r.setSnapshotQaRelation(encode(new cn.jualn.miniapp.module.content.bo.AdminPostProjection.Relation(question.getId(),c.getParentId()==null?c.getId():c.getParentId(),c.getReplyToCommentId(),c.getQuestionVersion())));
            }
            String ns=switch(c.getTargetType()) {case 1->"POST";case 2->"ACTIVITY";case 3->"PUBLIC_EVENT";default->null;};
            if(ns==null) return;
            r.setScene(ns.equals("POST")?"post-comment":ns.equals("ACTIVITY")?"activity-comment":"public-event-comment");
            r.setResourceNamespace(ns);r.setBusinessRevision(0L);r.setSnapshotTitle("评论 #"+c.getId());r.setSnapshotContent(c.getContent()==null?"":c.getContent());
            if(c.getImageUrl()!=null && !c.getImageUrl().isBlank()) {var a=new AdminReviewDetailBO.Attachment();a.setId(c.getId());a.setType("image");a.setName("评论图片");a.setUrl(c.getImageUrl());attachments.add(a);}
        }
        var textIds=result.getTextAuditLogIds().isEmpty()?(result.getTextAuditLogId()==null?List.<Long>of():List.of(result.getTextAuditLogId())):result.getTextAuditLogIds();
        var ids=new ArrayList<>(textIds);
        for(var item:result.getMediaItems()) ids.add(item.getAuditLogId());
        var growthLink=growth.link(r.getTargetType()==1?"post":"comment",r.getTargetId());if(growthLink!=null)r.setGrowthOrganizationId(growthLink.organizationId());
        r.setTaskId(ids.get(0));r.setExpectedChecks(ids.size());r.setSnapshotAttachments(encode(attachments));
        var previous=mapper.latest(r.getTargetType(),r.getTargetId());if(previous!=null) r.setPreviousTaskId(previous.getTaskId());
        mapper.insert(r);
        if(previous!=null && !terminal(previous.getState())) {previous.setState("superseded");previous.setSupersededByTaskId(r.getTaskId());mapper.save(previous);}
        for(long id:ids) {
            var check=new ContentSafetyCheck();check.setAuditLogId(id);check.setTaskId(r.getTaskId());
            if(textIds.contains(id)) check.setInputReference((r.getTargetType()==1?"text:title-and-content:":"text:content:")+textIds.indexOf(id));
            else {
                var inputMedia=result.getMediaItems().stream().filter(m->Objects.equals(m.getAuditLogId(),id)).findFirst().orElseThrow(this::unavailable);
                var attachment=attachments.stream().filter(m->Objects.equals(m.getUrl(),inputMedia.getMediaUrl())).findFirst().orElseThrow(this::unavailable);
                check.setInputReference("attachment:"+attachment.getId());
            }
            mapper.insertCheck(check);mapper.link(id,r.getTaskId());
        }

    }
    public static boolean terminal(String state) {return Set.of("approved","rejected","superseded","cancelled","invalidated").contains(state);}
    /** Parent -> root -> comment -> review is the same order as domain comment writes. */
    public String invalidation(ContentSafetyReview r,boolean lock) {
        if(lock && r.getTargetType()==4)mentorAnswers.getObject().guardPublication(r.getTargetId());
        if(r.getTargetType()==1) {
            var p=lock?mapper.lockPost(r.getTargetId()):mapper.post(r.getTargetId());
            if(p==null || p.getDeletedAt()!=null || p.getStatus()==4) return "target-deleted";
            if(!Objects.equals(p.getAuditRevision(),r.getBusinessRevision()) || !Objects.equals(p.getContent(),r.getSnapshotContent()) || !Objects.equals(p.getTitle()==null?"":p.getTitle(),r.getSnapshotTitle())) return "target-changed";
            if(p.getStatus()!=1 && p.getStatus()!=2) return "target-unpublished";
            return null;
        }
        var c=mapper.comment(r.getTargetId());if(c==null) return "target-deleted";
        boolean subject=switch(c.getTargetType()) {
            case 1 -> {var p=lock?mapper.lockPost(c.getTargetId()):mapper.post(c.getTargetId());yield p!=null && p.getDeletedAt()==null && p.getStatus()==2 && !Boolean.TRUE.equals(p.getAdminHidden()) && (Integer.valueOf(1).equals(p.getAuditStatus()) || Boolean.TRUE.equals(p.getCommunityVersion()) && p.getTemporaryVisibleUntil()!=null && LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")).isBefore(p.getTemporaryVisibleUntil()));}
            case 2 -> "1".equals(lock?mapper.lockActivity(c.getTargetId()):mapper.activity(c.getTargetId()));
            case 3 -> "1".equals(lock?mapper.lockEvent(c.getTargetId()):mapper.event(c.getTargetId()));
            default -> false;
        };
        if(subject && c.getTargetType()==1 && c.getQuestionVersion()!=null) {var question=lock?mapper.lockPost(c.getTargetId()):mapper.post(c.getTargetId());if(question==null || !Objects.equals(c.getQuestionVersion(),question.getQuestionVersion()))return "subject-unavailable";}
        if(!subject) return "subject-unavailable";
        if(c.getParentId()!=null) {var root=lock?mapper.lockComment(c.getParentId()):mapper.comment(c.getParentId());if(root==null || (!(root.getStatus()==1 && root.getDeletedAt()==null) && !(Boolean.TRUE.equals(c.getCommunityThread()) && java.util.Set.of(3,4).contains(root.getStatus()))) || !Objects.equals(root.getTargetType(),c.getTargetType()) || !Objects.equals(root.getTargetId(),c.getTargetId())) return "subject-unavailable";}
        if(lock) c=mapper.lockComment(r.getTargetId());
        if(c==null || c.getDeletedAt()!=null || c.getStatus()==3 || c.getStatus()==4) return "target-deleted";
        if(!Objects.equals(c.getContent()==null?"":c.getContent(),r.getSnapshotContent())) return "target-changed";
        var expected=attachments(r).stream().findFirst().map(AdminReviewDetailBO.Attachment::getUrl).orElse(null);
        if(!Objects.equals(expected,c.getImageUrl())) return "target-changed";
        return c.getStatus()==0?null:"target-unpublished";
    }
    public String state(ContentSafetyReview r) {return !terminal(r.getState()) && invalidation(r,false)!=null?"invalidated":r.getState();}
    public Round projection(ContentSafetyReview r) {
        String state=state(r);return new Round(r.getSubmissionVersion(),r.getScene(),r.getResourceNamespace(),r.getGrowthOrganizationId()==null?new BusinessTarget("PLATFORM"):new BusinessTarget("ORGANIZATION",r.getGrowthOrganizationId()),r.getPreviousTaskId()==null?"initial":"resubmission",state,str(r.getPreviousTaskId()),str(r.getSupersededByTaskId()),r.getDecisionOrigin(),"invalidated".equals(state)?(r.getInvalidationReason()!=null?r.getInvalidationReason():invalidation(r,false)):null);
    }
    private static String str(Long value) {return value==null?null:value.toString();}
    public List<AdminReviewDetailBO.Attachment> attachments(ContentSafetyReview r) {return List.of(decode(r.getSnapshotAttachments(),AdminReviewDetailBO.Attachment[].class));}
    public Evidence evidence(ContentSafetyReview r) {
        var checks=mapper.checks(r.getTaskId());
        boolean complete=checks.size()>=r.getExpectedChecks() && checks.stream().filter(c->checks.stream().noneMatch(n->Objects.equals(n.getSupersedesCheckId(),c.getAuditLogId()))).count()==r.getExpectedChecks()
            && checks.stream().filter(c->checks.stream().noneMatch(n->Objects.equals(n.getSupersedesCheckId(),c.getAuditLogId()))).allMatch(c->"succeeded".equals(c.getExecutionState()) && !"unknown".equals(c.getResult()) && c.getProviderFacts()!=null);
        return new Evidence(r.getSubmissionVersion(),complete,checks.stream().map(c->new Check(c.getAuditLogId().toString(),c.getInputReference(),true,c.getExecutionState(),c.getResult(),"wechat-content-security",c.getWxTraceId(),c.getProviderFacts()==null?null:decode(c.getProviderFacts(),Facts.class),str(c.getSupersedesCheckId()))).toList());
    }
    public static List<Check> activeChecks(Evidence evidence) {
        var replaced=evidence.checks().stream().map(Check::supersedesCheckId).filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        return evidence.checks().stream().filter(c->!replaced.contains(c.checkId())).toList();
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void observe(long log,String raw) {
        var r=mapper.forCheck(log);if(r==null) return;
        mapper.lock(r.getTaskId());
        var observation=ContentSafetyResults.interpret(json,raw);
        var current=mapper.check(log);
        var facts=observation.facts();
        if(current.getProviderFacts()!=null) {
            var previous=decode(current.getProviderFacts(),Facts.class);
            if(previous.detail()!=null && !Objects.equals(previous.detail(),facts.detail())) {
                var observations=new ArrayList<Object>();
                observations.add(decode(previous.detail(),Object.class));
                if(facts.detail()!=null) observations.add(decode(facts.detail(),Object.class));
                facts=new Facts(facts.resultCode(),facts.suggestion(),facts.matchedLabels(),encode(Map.of("observations",observations)));
            }
        }
        mapper.complete(log,observation.execution(),observation.result(),encode(facts));
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void finishManual(ContentSafetyReview r,boolean approved) {
        r.setState(approved?"approved":"rejected");r.setDecisionOrigin("manual");r.setDecidedAt(LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")));mapper.save(r);
    }
    /** Called by the durable audit.completed handler, inside its consumption transaction. */
    @Transactional(propagation=Propagation.MANDATORY)
    public void reconcile(long log) {
        var r=mapper.forCheck(log);if(r==null) return; // Legacy evidence never grants a fresh domain effect.
        String inactive=invalidation(r,true);r=mapper.lock(r.getTaskId());if(terminal(r.getState())) return;
        if(inactive!=null) {if(r.getTargetType()==4)mentorAnswers.getObject().invalidateChanged(r.getTargetId());r.setState("invalidated");r.setInvalidationReason(inactive);mapper.save(r);return;}
        var evidence=evidence(r);
        String next;
        if(activeChecks(evidence).stream().anyMatch(c->Set.of("failed","unknown").contains(c.executionState()))) next="check-failed";
        else if(!evidence.complete()) next="checking";
        else if(activeChecks(evidence).stream().anyMatch(c->"reject".equals(c.result()))) next="rejected";
        else if(activeChecks(evidence).stream().anyMatch(c->"review".equals(c.result()))) next="pending";
        else next="approved";
        if(r.getTargetType()==1 && Set.of("checking","check-failed").contains(next)
            && activeChecks(evidence).stream().anyMatch(c->Set.of("review","reject").contains(c.result()) || Set.of("unknown","failed").contains(c.executionState())))
            posts.getObject().applyContentSafety(r.getTargetId(),r.getBusinessRevision(),"check-failed".equals(next)?"check-failed":"pending");
        if(r.getTargetType()==4 && "POST".equals(r.getResourceNamespace()) && "check-failed".equals(next)) comments.getObject().applyContentSafety(r.getTargetId(),next);
        if(next.equals(r.getState())) return;
        if(Set.of("approved","rejected","pending").contains(next)) {
            if(r.getTargetType()==1) posts.getObject().applyContentSafety(r.getTargetId(),r.getBusinessRevision(),next);
            else comments.getObject().applyContentSafety(r.getTargetId(),next);
        }
        r.setState(next);if(next.equals("approved") || next.equals("rejected")) {r.setDecisionOrigin("automatic");r.setDecidedAt(LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")));}mapper.save(r);
    }
}
