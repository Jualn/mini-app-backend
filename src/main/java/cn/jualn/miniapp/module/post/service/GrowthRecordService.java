package cn.jualn.miniapp.module.post.service;

import cn.jualn.miniapp.module.post.GrowthProblems;
import cn.jualn.miniapp.module.post.PostProblems;
import cn.jualn.miniapp.module.post.bo.*;
import cn.jualn.miniapp.module.post.bo.GrowthRecordBO.*;
import cn.jualn.miniapp.module.post.bo.GrowthRecordBO.Record;
import cn.jualn.miniapp.module.post.entity.Post;
import cn.jualn.miniapp.module.post.mapper.*;
import cn.jualn.miniapp.module.media.service.GrowthMediaService;
import cn.jualn.miniapp.module.media.bo.GrowthMediaBO.*;
import cn.jualn.miniapp.module.user.service.*;
import cn.jualn.miniapp.module.audit.service.*;
import cn.jualn.miniapp.module.audit.bo.AuditReserveBO;
import cn.jualn.miniapp.module.audit.enums.AuditScene;
import cn.jualn.miniapp.module.article.service.ArticleCursor;
import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.common.constant.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import java.time.*;
import java.util.*;

@Service @RequiredArgsConstructor
public class GrowthRecordService {
    private final GrowthRecordMapper links;
    private final PostCommunityMapper community;
    private final PostMapper posts;
    private final PostCommunityService receipts;
    private final GrowthRecordAccess access;
    private final GrowthQualification qualification;
    private final GrowthMediaService media;
    private final PostOrganizationService organization;
    private final PostRepresentationTags tags;
    private final PostSafetyChecks safety;
    private final CommunitySafetyStates safetyStates;
    private final AuditReservationService audit;
    private final UserAccountService accounts;
    private final UserService users;
    private final PlatformTransactionManager transactions;

    public GrowthQualification.Context context() {long actor=actor();accounts.assertLoginAllowed(actor);var value=qualification.context(actor);return canPublish(actor)?value:new GrowthQualification.Context(value.personId(),value.evaluatedAt(),List.of());}
    public Record get(long id) {return execute(status->read(id,actor()));}
    private Record read(long id,long actor) {
        accounts.assertLoginAllowed(actor);var p=posts.selectById(id);var link=access.link(id);
        if(p==null || link==null || !access.readable(p,actor,false))throw GrowthProblems.missing();
        var life=lifecycle(p);var dict=organization.project(List.of(id),p.getCategoryId()==null?List.of():List.of(p.getCategoryId()));
        var actions=new ArrayList<String>();
        if(Objects.equals(p.getUserId(),actor) && link.personId().equals(qualification.currentPerson(actor))) {
            actions.add("DELETE");
            if(!Boolean.TRUE.equals(p.getAdminHidden()) && qualification.context(actor).organizations().stream().anyMatch(o->o.organizationId().equals(link.organizationId()) && o.allowedRecordTypes().contains(link.recordType())) && canPublish(actor))actions.add("EDIT");
        }
        boolean publicVersion=access.readable(p,actor,true);
            if(publicVersion){if(canPublish(actor))actions.add("COMMENT");actions.addAll(List.of("LIKE","FAVORITE","REPORT","SHARE"));}
        return new Record(link,users.getSimpleInfo(p.getUserId()),p.getTitle(),p.getContent(),media.current(id,life.safety()),PostCommunityService.offset(p.getPublishedAt()),p.getCategoryId()==null?null:dict.categories().get(p.getCategoryId()),dict.topics().getOrDefault(id,List.of()),life,List.copyOf(actions),publicVersion?posts.communityLikeCount(id):0,publicVersion?posts.communityCommentCounts(List.of(id)).stream().findFirst().map(PostDiscussionCount::count).orElse(0):0,publicVersion?Objects.requireNonNullElse(p.getViewCount(),0):0,publicVersion?posts.communityShareCount(id):0);
    }
    public Lifecycle lifecycle(Post p) {
        var base=PostCommunityService.lifecycle(p,PostCommunityService.now());String state=safetyStates.current(1,p.getId(),p.getAuditRevision(),base.safety());
        String visible=switch(base.visibility()){case "PUBLIC"->"ORGANIZATION";case "TEMPORARY"->"TEMPORARY_ORGANIZATION";default->"PRIVATE";};
        if(!qualification.organizationActive(access.link(p.getId()).organizationId()))visible="PRIVATE";
        return new Lifecycle(base.acceptedAt(),base.submissionVersion(),"PRIVATE".equals(visible)?null:base.effectiveVersion(),visible,state,base.authorDeleted(),base.adminHidden(),"TEMPORARY_ORGANIZATION".equals(visible)?base.temporaryVisibleUntil():null);
    }
    public record Page(List<Record> items,boolean hasMore,String nextCursor) {}
    public Page page(boolean mine,String person,String org,String type,String cursor,int size) {
        if(size<1 || size>50 || type!=null && !Set.of("GROWTH","POLITICAL_BIRTHDAY").contains(type) || !mine && person==null && org==null)throw PostProblems.invalid("query","INVALID_VALUE","Specify organizationId or personId and valid filters");
        return execute(status->{
            long actor=actor();accounts.assertLoginAllowed(actor);String viewer=qualification.currentPerson(actor);
            String selected=mine?viewer:person;
            String binding=ArticleCursor.fingerprint(Arrays.asList("growth",actor,viewer,mine,person,org,type));String value=decodeCursor(cursor,binding);
            LocalDateTime time=null;Long id=null;
            if(value!=null)try {var parts=value.split("\\|",-1);if(parts.length!=2)throw new IllegalArgumentException();time=LocalDateTime.parse(parts[0]);id=Long.valueOf(parts[1]);if(id<1)throw new IllegalArgumentException();}catch(IllegalArgumentException e){throw cursorProblem("INVALID_CURSOR");}
            if(mine && viewer==null)return new Page(List.of(),false,null);
            if(org!=null && !qualification.member(actor,org))throw GrowthProblems.missing();
            var allowed=new ArrayList<String>();
            for(String target:org!=null?List.of(org):links.organizations(selected))if(qualification.member(actor,target))allowed.add(target);
            if(allowed.isEmpty())allowed.add("");
            var ids=links.page(new GrowthRecordMapper.Query(selected,org,type,viewer,allowed,time,id,size+1));boolean more=ids.size()>size;var page=more?ids.subList(0,size):ids;
            var result=page.stream().map(post->read(post,actor)).toList();String next=null;
            if(more){var last=posts.selectById(page.get(page.size()-1));next=ArticleCursor.encode(last.getPublishedAt()+"|"+last.getId(),binding);}
            return new Page(result,more,next);
        });
    }
    public Record create(Create input,String key) {
        Create c=input==null?null:new Create(input.organizationId(),input.recordType(),input.title(),input.content(),input.mediaIds(),input.categoryId(),input.topicIds()==null?List.of():input.topicIds());
        GrowthMediaService.key(key);validate(c);long actor=actor();String hash=receipts.fingerprint(c);String intent=ArticleCursor.fingerprint(key);
        var old=receipts.creationReceipt("GROWTH",intent);
        if(old!=null){if(!hash.equals(old.fingerprint()))throw conflict();return get(old.targetId());}
        accounts.assertCommunityContentAllowed(actor);String person=qualification.requirePublisher(actor,c.organizationId(),c.recordType());
        Scope scope=new Scope(actor,person,c.organizationId(),c.recordType(),null);var items=media.prepare(scope,c.mediaIds());content(c.content(),items.size());
        safety.check(actor,0,Objects.toString(c.title(),"")+"\n"+c.content(),List.of());
        long id=execute(status->{
            qualification.guard(actor);publisher(scope);accounts.lockCommunityWriter(actor);
            long created=receipts.intent("GROWTH",intent,hash,()->{
                organization.validateReferences("NORMAL",c.categoryId(),c.topicIds()==null?List.of():c.topicIds());
                var now=PostCommunityService.now();var p=Post.builder().userId(actor).postType("NORMAL").title(c.title()).content(c.content()).categoryId(c.categoryId()).revision(0L).auditRevision(0L).communityVersion(true).adminHidden(false).acceptedAt(now).temporaryVisibleUntil(now.plusMinutes(10)).status(2).auditStatus(0).publishedAt(now).build();
                posts.insert(p);links.insert(new Link(p.getId(),person,c.organizationId(),c.recordType()));organization.replaceTopics(p.getId(),c.topicIds()==null?List.of():c.topicIds());media.bind(scope,p.getId(),c.mediaIds());
                var result=audit.reserveAuditLogs(AuditReserveBO.builder().auditScene(AuditScene.POST).targetId(p.getId()).textContent(Objects.toString(c.title(),"")+"\n"+c.content()).mediaItems(items.stream().map(m->AuditReserveBO.MediaItem.builder().mediaType(m.getType()).mediaUrl(m.getUrl()).build()).toList()).build());
                if(!result.hasAuditTask())posts.update(null,new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Post>().eq(Post::getId,p.getId()).set(Post::getAuditStatus,1));
                return p.getId();
            });
            beforeCommit(()->publisher(scope));return created;
        });return get(id);
    }
    public Upload upload(UploadCommand c) {
        if(c==null || !validType(c.recordType()) || c.organizationId()==null)throw PostProblems.invalid("body","INVALID_VALUE","Growth upload context required");
        long actor=actor();accounts.assertCommunityContentAllowed(actor);String person=qualification.requirePublisher(actor,c.organizationId(),c.recordType());
        var scope=new Scope(actor,person,c.organizationId(),c.recordType(),c.postId());return media.upload(scope,c.files(),()->authorizeScope(scope));
    }
    public File register(String upload,int index) {long actor=actor();var scope=media.scope(actor,upload,index);return media.register(scope,upload,index,()->authorizeScope(scope));}
    private void authorizeScope(Scope scope) {qualification.guard(scope.user());publisher(scope);if(scope.post()!=null){var post=posts.lockPost(scope.post());access.original(post,scope.user(),true);var link=access.link(scope.post());if(!link.organizationId().equals(scope.organization()) || !link.recordType().equals(scope.type()) || Boolean.TRUE.equals(post.getAdminHidden()))throw GrowthProblems.missing();}beforeCommit(()->publisher(scope));}
    private void publisher(Scope s) {accounts.assertCommunityContentAllowed(s.user());if(!s.person().equals(qualification.requirePublisher(s.user(),s.organization(),s.type())))throw GrowthProblems.forbidden();}
    public PostVersionedBO<Editable> editable(long id) {return execute(status->{long actor=actor();qualification.guard(actor);var p=posts.lockPost(id);access.original(p,actor,false);return version(p);});}
    public record Management(Link growthRecord,Lifecycle growthLifecycle,List<File> growthMedia,String editableEtag,List<PostCommunityBO.Management> managementHistory) {}
    public Management management(long id,long actor,String duty,String permission) {
        access.requireAdmin(id,actor,duty,permission);var p=posts.includingDeleted(id);if(p==null)throw GrowthProblems.missing();
        var life=lifecycle(p);return new Management(access.link(id),life,media.current(id,life.safety()),version(p).etag(),community.historyFor(id));
    }
    PostVersionedBO<Editable> version(Post p) {var link=access.link(p.getId());var dict=organization.project(List.of(p.getId()),List.of());var value=new Editable(p.getId(),link.personId(),link.organizationId(),link.recordType(),p.getTitle(),p.getContent(),media.currentIds(p.getId()),p.getCategoryId(),dict.topicIds().getOrDefault(p.getId(),List.of()));return new PostVersionedBO<>(value,tags.tag(value,p.getRevision()));}
    public record Edit(String title,String content,List<Long> mediaIds,Long categoryId,List<Long> topicIds,Set<String> fields) {}
    public PostVersionedBO<Editable> edit(long id,Edit command,String ifMatch) {
        var before=editable(id);long actor=actor();var old=before.value();var scope=new Scope(actor,old.personId(),old.organizationId(),old.recordType(),id);publisher(scope);PostProblems.match(ifMatch,before.etag());
        if(command==null || command.fields().isEmpty() || command.fields().contains("attachmentItems"))throw PostProblems.invalid("body","INVALID_VALUE","Use growth mediaIds");
        var fields=command.fields();String title=fields.contains("title")?command.title():old.title();String content=fields.contains("content")?command.content():old.content();List<Long> ids=fields.contains("mediaIds")?command.mediaIds():old.mediaIds();Long category=fields.contains("categoryId")?command.categoryId():old.categoryId();List<Long> topics=fields.contains("topicIds")?command.topicIds():old.topicIds();
        validate(new Create(old.organizationId(),old.recordType(),title,content,ids,category,topics));var items=media.prepare(scope,ids);content(content,items.size());
        safety.check(actor,id,Objects.toString(title,"")+"\n"+content,items);
        return execute(status->{
            qualification.guard(actor);publisher(scope);var p=posts.lockPost(id);access.original(p,actor,true);PostProblems.match(ifMatch,version(p).etag());if(Boolean.TRUE.equals(p.getAdminHidden()))throw ContractProblemException.conflict("post-moderation-conflict","Managed hidden content cannot be edited");
            organization.validateReferences("NORMAL",fields.contains("categoryId")?category:null,fields.contains("topicIds")?topics:null);
            if(fields.contains("mediaIds"))media.bind(scope,id,ids);if(fields.contains("topicIds"))organization.replaceTopics(id,topics);
            if(posts.applyEdit(new PostEffectiveEditBO(id,title,content,category,p.getRevision(),true))!=1)throw ContractProblemException.preconditionFailed();
            beforeCommit(()->publisher(scope));return version(posts.lockPost(id));
        });
    }
    private boolean canPublish(long actor){var s=accounts.getUserAuthInfo(actor).getStatus();return s!=cn.jualn.miniapp.module.user.enums.UserStatus.MUTED && s!=cn.jualn.miniapp.module.user.enums.UserStatus.BANNED;}
    private static void validate(Create c){if(c==null || c.organizationId()==null || c.organizationId().isBlank() || !validType(c.recordType()) || c.content()==null || c.content().length()>5000 || c.title()!=null && c.title().length()>128 || c.mediaIds()==null || c.topicIds()!=null && c.topicIds().size()>3)throw PostProblems.invalid("body","INVALID_VALUE","Invalid growth declaration");}
    private static boolean validType(String type){return type!=null && Set.of("GROWTH","POLITICAL_BIRTHDAY").contains(type);}
    private static void content(String value,int media){if(value.isBlank() && media==0)throw PostProblems.invalid("content","REQUIRED","Text or growth media is required");}
    private static long actor(){return PostCommunityService.actor();}
    private <T> T execute(org.springframework.transaction.support.TransactionCallback<T> action){try{return tx().execute(action);}catch(org.springframework.dao.DataAccessException failure){throw GrowthProblems.source();}}
    private TransactionTemplate tx(){var tx=new TransactionTemplate(transactions);tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);return tx;}
    private static void beforeCommit(Runnable action){TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void beforeCommit(boolean readOnly){action.run();}});}
    private static ContractProblemException conflict(){return ContractProblemException.conflict("growth-idempotency-conflict","Key already belongs to another growth request");}
    private static String decodeCursor(String cursor,String binding){
        if(cursor==null)return null;
        String[] parts;
        try{if(cursor.length()>2048 || !cursor.matches("[A-Za-z0-9_-]+"))throw new IllegalArgumentException();String decoded=new String(Base64.getUrlDecoder().decode(cursor),java.nio.charset.StandardCharsets.UTF_8);parts=decoded.split("\n",-1);if(parts.length!=3 || !"article-v1".equals(parts[0]) || !parts[1].matches("[0-9a-f]{64}") || !ArticleCursor.encode(parts[2],parts[1]).equals(cursor))throw new IllegalArgumentException();}
        catch(IllegalArgumentException failure){throw cursorProblem("INVALID_CURSOR");}
        if(!binding.equals(parts[1]))throw cursorProblem("CURSOR_QUERY_MISMATCH");return parts[2];
    }
    private static ContractProblemException cursorProblem(String code){return ContractProblemException.validation(new ContractProblemException.Violation("query","cursor",code,"Invalid cursor for this growth query"));}
}
