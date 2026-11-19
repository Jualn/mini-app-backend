package cn.jualn.miniapp.module.post.service;

import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.module.post.PostProblems;
import cn.jualn.miniapp.module.post.bo.*;
import cn.jualn.miniapp.module.post.bo.PostCommunityBO.*;
import cn.jualn.miniapp.module.post.entity.Post;
import cn.jualn.miniapp.module.post.mapper.*;
import cn.jualn.miniapp.module.media.service.MediaQueries;
import cn.jualn.miniapp.module.user.service.UserService;
import cn.jualn.miniapp.module.user.service.UserAccountService;
import cn.jualn.miniapp.module.article.service.ArticleCursor;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;

/** Private community use cases. Receipts never contain a public content snapshot. */
@Service @RequiredArgsConstructor
public class PostCommunityService {
    @org.springframework.beans.factory.annotation.Autowired private org.springframework.beans.factory.ObjectProvider<MentorQuestions> mentorQuestions;
    private final PostMapper posts;
    private final PostCommunityMapper community;
    private final PostService writer;
    private final PostSafetyChecks safety;
    private final PostPublicAccess access;
    private final PostOrganizationService organization;
    private final PostCommentPermissions permissions;
    private final MediaQueries media;
    private final cn.jualn.miniapp.module.media.service.MediaService mediaWriter;
    private final UserService users;
    private final cn.jualn.miniapp.module.interact.service.InteractionLikeQueries likes;
    private final UserAccountService accounts;
    private final PlatformTransactionManager transactions;
    private final ObjectMapper json;
    private final cn.jualn.miniapp.module.audit.service.CommunitySafetyStates safetyStates;
    @org.springframework.beans.factory.annotation.Autowired private org.springframework.beans.factory.ObjectProvider<GrowthRecordAccess> growth;

    public Submission create(PostCreateBO command, String key) {
        long actor = actor();
        validateKey(key);
        String hash = fingerprint(command);
        if (key != null) {
            var old = community.receipt(actor, "POST", key);
            if (old != null) { same(old, hash); return submission(owned(old.targetId())); }
        }
        command.setAttachmentItems(mediaWriter.prepareNewAttachments(TargetType.POST,command.getAttachmentItems()));
        accounts.assertCommunityContentAllowed(actor);
        boolean normal = command.getPostType() == cn.jualn.miniapp.module.post.PostType.NORMAL;
        if (!normal && (command.getContent()==null || command.getContent().isBlank())) throw PostProblems.invalid("content","REQUIRED","A question requires text");
        {
            PostContentRules.validate(command.getContent(), command.getAttachmentItems());
            String text = Objects.toString(command.getTitle(), "") + "\n" + Objects.toString(command.getContent(), "");
            safety.check(actor, 0, text.isBlank() ? null : text, List.of());
        }
        return new TransactionTemplate(transactions).execute(tx -> {
            Long id = intent("POST", key, hash, () -> writer.createPost(command).getId());
            return submission(owned(id));
        });
    }

    /** Joins the caller transaction; the unique key serializes simultaneous requests. */
    @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public Long intent(String operation, String key, String hash, Supplier<Long> create) {
        long actor = actor(); validateKey(key);
        if (key == null) return create.get();
        community.reserve(actor, operation, key, hash);
        var old = community.lockReceipt(actor, operation, key); if("GROWTH".equals(operation) && !hash.equals(old.fingerprint()))throw cn.jualn.miniapp.module.post.GrowthProblems.problem(org.springframework.http.HttpStatus.CONFLICT,"growth-idempotency-conflict");same(old, hash);
        if (old.targetId() != null) return old.targetId();
        long id = create.get();
        if (community.complete(actor, operation, key, id) != 1) throw new IllegalStateException("Creation receipt changed");
        return id;
    }

    public CreationReceipt creationReceipt(String operation,String key) { validateKey(key);var r=community.receipt(actor(),operation,key);return r==null?null:new CreationReceipt(r.fingerprint(),r.targetId()); }

    public Submission receipt(String key) {
        validateKey(key);
        var receipt = community.receipt(actor(), "POST", key);
        if (receipt == null || receipt.targetId() == null) throw missing();
        return submission(owned(receipt.targetId()));
    }

    @org.springframework.transaction.annotation.Transactional(isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Mine mine(long id) { var post = owned(id); return new Mine(representation(post), submission(post)); }
    public Page<Mine> mine(String cursor,int size) {return mine(cursor,size,"NORMAL");}
    @org.springframework.transaction.annotation.Transactional(isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Page<Mine> mine(String cursor, int size,String postType) {
        typeFilter(postType);
        size(size); long actor = actor();
        String context = "my-posts/" + actor+"/"+postType;
        String pos = ArticleCursor.decode(cursor, context);
        Long after = id(pos);
        var mineQuery=new LambdaQueryWrapper<Post>();PostPublicAccess.excludeGrowth(mineQuery);
        var rows = posts.selectList(mineQuery.eq(Post::getUserId, actor)
                .eq(!"ALL".equals(postType),Post::getPostType, postType).lt(after != null, Post::getId, after)
                .orderByDesc(Post::getId).last("LIMIT " + (size + 1)));
        boolean more = rows.size() > size;
        var page = more ? rows.subList(0, size) : rows;
        return new Page<>(page.stream().map(p -> new Mine(representation(p), submission(p))).toList(), more,
                more ? ArticleCursor.encode(page.get(page.size()-1).getId().toString(), context) : null);
    }

    private Post owned(Long id) {
        long actor = actor();
        Post p = id == null ? null : posts.selectById(id);
        if(p!=null && growth.getObject().link(id)!=null)throw missing();
        if (p == null || !Objects.equals(actor, p.getUserId()) || !Set.of("NORMAL","MENTOR_QA").contains(p.getPostType())) throw missing();
        return p;
    }

    private Submission submission(Post p) {
        var base=lifecycle(p,now());
        var state=safetyStates.current(1,p.getId(),p.getAuditRevision(),base.safety());
        var life=new Lifecycle(base.acceptedAt(),base.submissionVersion(),base.effectiveVersion(),base.visibility(),state,base.authorDeleted(),base.adminHidden(),base.temporaryVisibleUntil());
        var actions = new ArrayList<String>();
        if (!life.authorDeleted()) {
            actions.add("VIEW"); actions.add("DELETE");
            if (!life.adminHidden() && canWrite(p.getUserId())) {
                actions.add("EDIT"); if (!"PASSED".equals(life.safety())) actions.add("RESUBMIT");
            }
        }
        return new Submission(p.getId(), life, actions,notice(life.safety(),life.adminHidden(),life.acceptedAt()));
    }

    public static Notice notice(String safety,boolean hidden,OffsetDateTime at) {
        if(hidden)return new Notice("ADMIN_HIDDEN","内容已由管理员隐藏，可查看处理结果或主动删除。",at);
        return switch(safety) {
            case "CHECKING" -> new Notice("CHECKING","内容正在进行安全检查。",at);
            case "REJECTED" -> new Notice("CHECK_REJECTED","内容未通过安全检查，请修改后重新提交。",at);
            case "REVIEW_REQUIRED" -> new Notice("REVIEW_REQUIRED","内容需要安全复核。",at);
            case "CHECK_FAILED" -> new Notice("CHECK_UNAVAILABLE","安全检查未能确认，请稍后核对或重新提交。",at);
            default -> null;
        };
    }
    public static Lifecycle lifecycle(Post p, LocalDateTime now) {
        boolean deleted = p.getDeletedAt() != null;
        boolean hidden = Boolean.TRUE.equals(p.getAdminHidden());
        String safety = Integer.valueOf(1).equals(p.getAuditStatus()) ? "PASSED" : Integer.valueOf(2).equals(p.getAuditStatus()) ? "REJECTED" : Integer.valueOf(1).equals(p.getStatus()) ? "REVIEW_REQUIRED" : "CHECKING";
        boolean passed = "PASSED".equals(safety) && Integer.valueOf(2).equals(p.getStatus());
        boolean temporary = Boolean.TRUE.equals(p.getCommunityVersion()) && "CHECKING".equals(safety)
                && Integer.valueOf(2).equals(p.getStatus()) && p.getTemporaryVisibleUntil() != null && now.isBefore(p.getTemporaryVisibleUntil());
        String visibility = deleted || hidden ? "PRIVATE" : passed ? "PUBLIC" : temporary ? "TEMPORARY" : "PRIVATE";
        String version = p.getId() + ":" + Objects.requireNonNullElse(p.getAuditRevision(), 0L);
        LocalDateTime accepted = p.getAcceptedAt() != null ? p.getAcceptedAt() : p.getCreatedAt() != null ? p.getCreatedAt() : p.getPublishedAt();
        return new Lifecycle(offset(accepted), version, "PRIVATE".equals(visibility) ? null : version,
                visibility, safety, deleted, hidden, temporary ? offset(p.getTemporaryVisibleUntil()) : null);
    }

    private PostDetailBO representation(Post p) {
        var projection = organization.project(List.of(p.getId()), p.getCategoryId() == null ? List.of() : List.of(p.getCategoryId()));
        return PostDetailBO.builder().id(p.getId()).postType(p.getPostType()).title(p.getTitle()).content(p.getContent())
                .author(users.getSimpleInfo(p.getUserId())).attachments(media.listSimpleAttachments(TargetType.POST,p.getId()))
                .category(p.getCategoryId()==null?null:projection.categories().get(p.getCategoryId())).topics(projection.topics().getOrDefault(p.getId(),List.of()))
                .qaSummary("MENTOR_QA".equals(p.getPostType())?mentorQuestions.getObject().summary(p.getId()):null).commentPermission("MENTOR_QA".equals(p.getPostType())?permissions.answer(UserContext.getUserId(),p.getPostType()):writePermission()).publishedAt(p.getPublishedAt())
                .commentCount(PostPublicAccess.visible(p,now())?posts.communityCommentCounts(List.of(p.getId())).stream().findFirst().map(PostDiscussionCount::count).orElse(0):0).viewCount(Objects.requireNonNullElse(p.getViewCount(),0))
                .likeCount(posts.communityLikeCount(p.getId())).liked(likes.ownRelation(TargetType.POST,p.getId())).build();
    }

    public FavoriteState favorite(long post, Boolean value) {
        long actor = actor();
        var transaction=new TransactionTemplate(transactions);transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        return transaction.execute(tx -> {
            if(growth.getObject().link(post)!=null)access.require(post,true);
            if (!Boolean.FALSE.equals(value)) normal(post, true);
            if (Boolean.TRUE.equals(value)) community.favorite(actor,post);
            else if (Boolean.FALSE.equals(value)) community.unfavorite(actor,post);
            return new FavoriteState(post,community.favorited(actor,post));
        });
    }
    public Page<Saved> saved(boolean history,String cursor,int size) {return saved(history,cursor,size,"NORMAL");}
    public Page<Saved> saved(boolean history, String cursor, int size,String postType) {
        typeFilter(postType);
        size(size); long actor = actor(); String context = (history ? "history/" : "favorites/") + actor+"/"+postType;
        String pos = ArticleCursor.decode(cursor,context); LocalDateTime time = null; Long id = null;
        if (pos != null) try { var parts=pos.split("\\|",-1); if(parts.length!=2) throw new IllegalArgumentException(); time=LocalDateTime.parse(parts[0]); id=Long.valueOf(parts[1]); if(id<1) throw new IllegalArgumentException(); }
        catch(IllegalArgumentException failure) { throw ArticleCursor.invalid(); }
        var rows = history ? community.views(actor,now().minusDays(90),time,id,size+1,postType) : community.favorites(actor,time,id,size+1,postType);
        boolean more=rows.size()>size; var page=more?rows.subList(0,size):rows;
        return new Page<>(page.stream().map(r -> saved(r,history)).toList(),more,
                more?ArticleCursor.encode(page.get(page.size()-1).time()+"|"+page.get(page.size()-1).postId(),context):null);
    }
    private Saved saved(PostCommunityMapper.SavedRow r,boolean history) {
        Post p=posts.selectById(r.postId());
        boolean visible=p!=null && !"PRIVATE".equals(lifecycle(p,now()).visibility());
        return new Saved(r.postId(),visible?"AVAILABLE":"UNAVAILABLE",visible?representation(p):null,
                history?null:offset(r.time()),history?offset(r.time()):null,history?offset(r.time().plusDays(90)):null);
    }
    public OffsetDateTime clearHistory() { return new TransactionTemplate(transactions).execute(tx -> { long actor=actor();  community.clearViews(actor); return offset(now()); }); }
    @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public boolean recordOpening(long actor,long post) {
        var p=posts.lockPost(post);
        if(p==null || !Set.of("NORMAL","MENTOR_QA").contains(p.getPostType())) return false;
        access.require(post,true);
        community.viewed(actor,post,now());
        return true;
    }
    @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void countOpening(long post) { posts.incrementViewCount(post,1L); }
    public boolean isNormal(long post) { var p=posts.includingDeleted(post); return p!=null && Set.of("NORMAL","MENTOR_QA").contains(p.getPostType()); }
    public void normal(long post,boolean lock) { if(!Set.of("NORMAL","MENTOR_QA").contains(access.require(post,lock).postType())) throw missing(); }
    public String fingerprint(Object value) { try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(value))); } catch(java.security.GeneralSecurityException | com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException(e); } }
    private void same(PostCommunityMapper.Receipt r,String hash) { if(!hash.equals(r.fingerprint())) throw ContractProblemException.conflict("idempotency-key-reused","Key already belongs to another request"); }
    public static void validateKey(String key) { if(key!=null && (key.isBlank() || key.length()>128)) throw PostProblems.invalid("Idempotency-Key","INVALID_VALUE","Invalid intent key"); }
    public PostWritePermission writePermission() { Long actor=UserContext.getUserId();return actor!=null && canWrite(actor)?PostWritePermission.allowed():PostWritePermission.denied("NOT_AUTHORIZED"); }
    private boolean canWrite(long actor) { var status=accounts.getUserAuthInfo(actor).getStatus(); return status!=cn.jualn.miniapp.module.user.enums.UserStatus.MUTED && status!=cn.jualn.miniapp.module.user.enums.UserStatus.BANNED; }
    public static long actor() { Long actor=UserContext.getUserId(); if(actor==null) throw new BusinessException(ResultCode.UNAUTHORIZED); return actor; }
    private static Long id(String value) { if(value==null)return null; try { long id=Long.parseLong(value); if(id>0)return id; } catch(NumberFormatException ignored) { } throw ArticleCursor.invalid(); }
    public static void typeFilter(String type) {if(!Set.of("NORMAL","MENTOR_QA","ALL").contains(type))throw PostProblems.invalid("postType","INVALID_VALUE","Unsupported type");}
    public static void size(int size) { if(size<1||size>50)throw PostProblems.invalid("pageSize","INVALID_VALUE","Use pageSize 1..50"); }
    public static LocalDateTime now() { return LocalDateTime.now(ZoneId.of("Asia/Shanghai")); }
    public static OffsetDateTime offset(LocalDateTime time) { return time==null?null:time.atOffset(ZoneOffset.ofHours(8)); }
    private static BusinessException missing() { return new BusinessException(ResultCode.POST_NOT_FOUND); }
}
