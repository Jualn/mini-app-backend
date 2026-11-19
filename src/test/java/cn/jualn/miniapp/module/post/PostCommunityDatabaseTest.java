package cn.jualn.miniapp.module.post;

import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.module.post.bo.*;
import cn.jualn.miniapp.module.post.mapper.*;
import cn.jualn.miniapp.module.post.service.*;
import cn.jualn.miniapp.module.post.service.impl.PostServiceImpl;
import cn.jualn.miniapp.module.comment.service.*;
import cn.jualn.miniapp.module.comment.service.impl.*;
import cn.jualn.miniapp.module.comment.mapper.*;
import cn.jualn.miniapp.module.comment.bo.CommentCreateBO;
import cn.jualn.miniapp.module.audit.service.*;
import cn.jualn.miniapp.module.audit.mapper.*;
import cn.jualn.miniapp.module.media.TestMediaService;
import cn.jualn.miniapp.module.user.service.*;
import cn.jualn.miniapp.module.interact.service.*;
import cn.jualn.miniapp.module.interact.service.impl.InteractionAccess;
import cn.jualn.miniapp.module.interact.mapper.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.core.io.ClassPathResource;
import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real MySQL, Mapper, Spring transactions and MVC. Identity and external providers are explicit doubles. */
@EnabledIfSystemProperty(named="community.test.jdbcUrl",matches="jdbc:mysql://127[.]0[.]0[.]1:13381/post_community_test")
class PostCommunityDatabaseTest {
    static AnnotationConfigApplicationContext context;
    static JdbcTemplate db;
    static final java.util.List<java.util.Map<String,Object>> captures=new java.util.ArrayList<>();
    PostCommunityService community;
    @BeforeAll static void start() {
        var source=new DriverManagerDataSource(System.getProperty("community.test.jdbcUrl")+"?allowPublicKeyRetrieval=true&sslMode=DISABLED&serverTimezone=Asia/Shanghai","root","");
        var sessionProperties=new java.util.Properties();sessionProperties.setProperty("sessionVariables","time_zone='+08:00'");source.setConnectionProperties(sessionProperties);
        db=new JdbcTemplate(source);
        assertEquals("+08:00",db.queryForObject("SELECT @@session.time_zone",String.class));
        assertEquals(System.getProperty("community.test.serverUuid"),db.queryForObject("SELECT @@server_uuid",String.class));
        org.flywaydb.core.Flyway.configure().dataSource(source).load().migrate();
        context=new AnnotationConfigApplicationContext(Config.class);
        for(String table:List.of("post_creation_receipt","post_favorite","post_view_history","post_view_day","post_share_event"))db.update("DELETE FROM "+table+" WHERE actor_id IN(99101,99102)");
        db.update("DELETE FROM report_resolution WHERE handler_id=99102");db.update("DELETE FROM report WHERE reporter_id IN(99101,99102)");
        db.update("DELETE FROM comment_management_history WHERE comment_id IN(SELECT id FROM comment WHERE user_id IN(99101,99102))");
        db.update("DELETE FROM like_record WHERE user_id IN(99101,99102)");db.update("DELETE FROM share_record WHERE user_id IN(99101,99102)");
        db.update("DELETE FROM comment WHERE user_id IN(99101,99102)");
        db.update("DELETE FROM post_management_history WHERE post_id IN(SELECT id FROM post WHERE user_id=99101)");
        db.update("DELETE FROM post WHERE user_id=99101");
        db.update("INSERT IGNORE INTO user_profile(id,openid,nickname) VALUES(99101,'community-actor','actor'),(99102,'community-other','other')");
    }
    @AfterAll static void close() throws Exception { if(context!=null) {java.nio.file.Files.writeString(java.nio.file.Path.of("target/post-community/responses.json"),context.getBean(com.fasterxml.jackson.databind.ObjectMapper.class).writeValueAsString(captures));context.close();} }
    @BeforeEach void actor() {UserContext.setUserId(99101L);community=context.getBean(PostCommunityService.class);}
    @AfterEach void clear() {UserContext.clear();}
    PostCreateBO command(String text) {return PostCreateBO.builder().postType(PostType.NORMAL).content(text).build();}
    long create(String text) {return community.create(command(text),null).id();}
    CommentCreateBO comment(long post,Long parent,Long reply,String text) {var c=new CommentCreateBO();c.setTargetType(TargetType.POST);c.setTargetId(post);c.setParentId(parent);c.setReplyToCommentId(reply);c.setContent(text);return c;}
    void passed(long id) {db.update("UPDATE post SET audit_status=1 WHERE id=?",id);}

    @Test void preflightFailureIsZeroAcceptanceAndReceiptIsAtomic() {
        var safety=context.getBean(PostSafetyChecks.class);doThrow(new IllegalStateException("provider unavailable")).when(safety).check(anyLong(),anyLong(),eq("\nblocked"),any());
        long before=db.queryForObject("SELECT COUNT(*) FROM post",Long.class);
        assertThrows(IllegalStateException.class,()->community.create(command("blocked"),"blocked-key"));
        assertEquals(before,db.queryForObject("SELECT COUNT(*) FROM post",Long.class));
        assertNull(context.getBean(PostCommunityMapper.class).receipt(99101,"POST","blocked-key"));reset(safety);
        var first=community.create(command("accepted"),"intent-1");assertEquals(first.id(),community.create(command("accepted"),"intent-1").id());
        assertEquals(first.id(),community.receipt("intent-1").id());
        assertEquals("/problems/idempotency-key-reused",assertThrows(ContractProblemException.class,()->community.create(command("different"),"intent-1")).getType());
        UserContext.setUserId(99102L);assertThrows(RuntimeException.class,()->community.receipt("intent-1"));assertThrows(RuntimeException.class,()->community.mine(first.id()));
    }
    @Test void temporaryDeadlineIsExclusiveAndPrivateEditsKeepStrongConcurrency() {
        long id=create("temporary");var access=context.getBean(PostPublicAccess.class);access.require(id,false);
        var post=context.getBean(PostMapper.class).selectById(id);assertTrue(!post.getTemporaryVisibleUntil().isAfter(post.getAcceptedAt().plusMinutes(10)));
        assertFalse(PostPublicAccess.visible(post,post.getTemporaryVisibleUntil()));
        db.update("UPDATE post SET temporary_visible_until=DATE_SUB(NOW(6),INTERVAL 1 SECOND) WHERE id=?",id);
        assertThrows(RuntimeException.class,()->access.require(id,false));assertEquals("PRIVATE",community.mine(id).submission().lifecycle().visibility());
        var edits=context.getBean(PostEditingService.class);var old=edits.get(id);var saved=edits.edit(id,new PostEditBO(null,"corrected",null,null,null,Set.of("content")),old.etag());
        assertEquals("corrected",saved.value().content());assertNotEquals(old.etag(),saved.etag());
        assertThrows(ContractProblemException.class,()->edits.edit(id,new PostEditBO(null,"stale",null,null,null,Set.of("content")),old.etag()));
    }
    @Test void simultaneousSameIntentCommitsOnePost() throws Exception {
        var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try {
            Callable<Long> call=()->{UserContext.setUserId(99101L);try{start.await();return community.create(command("concurrent"),"race-key").id();}finally{UserContext.clear();}};
            var a=pool.submit(call);var b=pool.submit(call);start.countDown();assertEquals(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS));
            assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM post WHERE content='concurrent'",Integer.class));
        } finally {pool.shutdownNow();}
    }
    @Test void dailyViewsHistoryClearExpiryAndUnavailableFavoritesAreIndependent() {
        long id=create("viewed");passed(id);var views=context.getBean(InteractionViews.class);views.view(TargetType.POST,id);views.view(TargetType.POST,id);
        assertEquals(1,db.queryForObject("SELECT view_count FROM post WHERE id=?",Integer.class,id));
        assertEquals(1,community.saved(true,null,50).items().stream().filter(x->x.postId()==id).count());
        community.clearHistory();views.view(TargetType.POST,id);assertEquals(1,db.queryForObject("SELECT view_count FROM post WHERE id=?",Integer.class,id));
        community.favorite(id,true);community.favorite(id,true);assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM post_favorite WHERE post_id=?",Integer.class,id));
        db.update("UPDATE post_view_history SET last_viewed_at=DATE_SUB(NOW(6),INTERVAL 90 DAY) WHERE post_id=?",id);
        assertTrue(community.saved(true,null,50).items().stream().noneMatch(x->x.postId()==id));
        db.update("UPDATE post SET admin_hidden=TRUE WHERE id=?",id);
        var relation=community.saved(false,null,50).items().stream().filter(x->x.postId()==id).findFirst().orElseThrow();assertEquals("UNAVAILABLE",relation.availability());assertNull(relation.post());community.favorite(id,false);
    }
    @Test void threadPlaceholderRetainsNewReplyWithoutIdentityAndDoesNotExposeLegacyReply() {
        long post=create("discussion");passed(post);var writer=context.getBean(CommentService.class);var callback=context.getBean(CommentAuditCallback.class);
        long root=writer.createComment(comment(post,null,null,"root secret"));
        new TransactionTemplate(context.getBean(DataSourceTransactionManager.class)).execute(tx->{callback.activateCommentAfterAuditPass(root);return null;});
        long reply=writer.createComment(comment(post,root,root,"visible reply"));
        new TransactionTemplate(context.getBean(DataSourceTransactionManager.class)).execute(tx->{callback.activateCommentAfterAuditPass(reply);return null;});
        db.update("INSERT INTO comment(target_type,target_id,user_id,parent_id,content,status,audit_status,community_thread) VALUES(1,?,99102,?,'old hidden reply',1,1,FALSE)",post,root);
        writer.removeComment(root);
        var threads=context.getBean(CommunityCommentService.class);var node=(cn.jualn.miniapp.module.comment.bo.CommunityCommentBO.PlaceholderRoot)threads.roots(post,null,20).getList().get(0);
        assertEquals("AUTHOR_DELETED",node.state());var serialized=context.getBean(com.fasterxml.jackson.databind.ObjectMapper.class).valueToTree(node);assertFalse(serialized.has("author"));assertFalse(serialized.has("content"));assertEquals(1,node.replyCount());
        assertEquals(1,threads.replies(post,root,null,20,null).getList().size());assertEquals("AVAILABLE",threads.context(post,reply).availability());assertEquals("UNAVAILABLE",threads.context(post,root).availability());
        var item=threads.replies(post,root,null,20,null).getList().get(0);assertNull(item.replyToUser());
        assertThrows(RuntimeException.class,()->context.getBean(CommentTargetAccess.class).requireReplyRoot(root,TargetType.POST,post));
    }
    @Test void provenHideRestoreAndAuthorDeletionAreIndependent() {
        long id=create("managed");passed(id);var governance=context.getBean(PostGovernanceService.class);
        var action=AdminPostActionBO.builder().operatorId(99102L).postId(id).reason("management correction").build();
        governance.takeDownPost(action);assertEquals("PRIVATE",community.mine(id).submission().lifecycle().visibility());
        assertThrows(ContractProblemException.class,()->context.getBean(PostEditingService.class).get(id));
        db.update("UPDATE post SET audit_status=2 WHERE id=?",id);assertThrows(ContractProblemException.class,()->governance.restorePost(action));
        db.update("UPDATE post SET audit_status=1 WHERE id=?",id);governance.restorePost(action);context.getBean(PostPublicAccess.class).require(id,false);
        governance.takeDownPost(action);context.getBean(PostService.class).removePost(id);assertThrows(ContractProblemException.class,()->governance.restorePost(action));
        assertThrows(RuntimeException.class,()->community.mine(id));
    }
    @Test void categoryAndAuthorEditShareOneStrongVersion() {
        db.update("INSERT IGNORE INTO post_category(id,code,name) VALUES(99109,'COMMUNITY_TEST','synthetic')");
        db.update("INSERT IGNORE INTO post_category_type(category_id,post_type) VALUES(99109,'NORMAL')");
        long id=create("category");passed(id);var edits=context.getBean(PostEditingService.class);var before=edits.get(id);
        var storage=mock(cn.dev33.satoken.context.model.SaStorage.class);Map<String,Object> values=new HashMap<>();
        when(storage.get(anyString())).thenAnswer(i->values.get(i.getArgument(0)));when(storage.set(anyString(),any())).thenAnswer(i->{values.put(i.getArgument(0),i.getArgument(1));return storage;});when(storage.delete(anyString())).thenAnswer(i->{values.remove(i.getArgument(0));return storage;});
        cn.dev33.satoken.SaManager.getSaTokenContext().setContext(mock(cn.dev33.satoken.context.model.SaRequest.class),mock(cn.dev33.satoken.context.model.SaResponse.class),storage);
        try {
            cn.jualn.miniapp.common.security.AdminStpUtil.STP_LOGIC.switchTo(99102L);
            var after=context.getBean(PostCategoryMaintenance.class).set(id,99109L,before.etag());assertNotEquals(before.etag(),after.etag());
            assertEquals("category",db.queryForObject("SELECT content FROM post WHERE id=?",String.class,id));
            assertThrows(ContractProblemException.class,()->edits.edit(id,new PostEditBO(null,"old draft",null,null,null,Set.of("content")),before.etag()));
        } finally {cn.dev33.satoken.SaManager.getSaTokenContext().clearContext();}
    }

    @Test void authorPrivatePaginationAndNoStoreHttp() throws Exception {
        for(int i=0;i<22;i++)create("page "+i);
        var first=community.mine(null,20);assertTrue(first.hasMore());var next=community.mine(first.nextCursor(),20);assertFalse(next.items().isEmpty());
        var ids=new HashSet<Long>();first.items().forEach(x->assertTrue(ids.add(x.post().getId())));next.items().forEach(x->assertTrue(ids.add(x.post().getId())));
        var json=context.getBean(com.fasterxml.jackson.databind.ObjectMapper.class);
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new cn.jualn.miniapp.module.post.controller.PersonalPostController(community)).setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(json)).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/v1/users/me/posts")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control","no-store")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.items[0].submission.lifecycle.visibility").exists());
    }

    @Test void reportNoneTakeDownAndNotificationFailureCommitAsOneResolution() {
        long id=create("reported");passed(id);
        var reports=context.getBean(cn.jualn.miniapp.module.report.service.ReportService.class);
        var input=new cn.jualn.miniapp.module.report.bo.ReportCreateBO();input.setTargetType(TargetType.POST);input.setTargetId(id);input.setReason(cn.jualn.miniapp.module.report.enums.ReportReason.OTHER);
        long report=reports.createReport(input).getId();assertEquals("/problems/report-duplicate",assertThrows(ContractProblemException.class,()->reports.createReport(input)).getType());
        var resolver=context.getBean(cn.jualn.miniapp.module.report.service.AdminReportCaseService.class);
        var decision=cn.jualn.miniapp.module.report.bo.AdminReportCaseDecisionBO.builder().operatorId(99102L).targetType("post").targetId(id).decision(cn.jualn.miniapp.module.report.enums.ReportStatus.VIOLATION_HANDLED).contentAction("NONE").remark("verified retained").build();
        resolver.resolveCase(decision);assertEquals("UPHELD_RETAINED",reports.getMyReport(report).getOutcome());assertFalse(context.getBean(PostMapper.class).selectById(id).getAdminHidden());
        UserContext.setUserId(99102L);long later=reports.createReport(input).getId();assertEquals(cn.jualn.miniapp.module.report.enums.ReportStatus.PENDING,reports.getMyReport(later).getStatus());UserContext.setUserId(99101L);
        decision.setContentAction("TAKE_DOWN");
        var notifications=context.getBean(cn.jualn.miniapp.module.notify.service.NotifyService.class);
        doAnswer(i->{cn.jualn.miniapp.module.notify.payload.NotifyPayload payload=i.getArgument(0);if(payload.getType()==cn.jualn.miniapp.module.notify.enums.NotifyType.POST_REPORT_RESOLVED)throw new IllegalStateException("inbox acceptance failed");return null;}).when(notifications).processNotificationPayload(any());
        try {assertThrows(IllegalStateException.class,()->resolver.resolveCase(decision));assertFalse(context.getBean(PostMapper.class).selectById(id).getAdminHidden());assertEquals(0,db.queryForObject("SELECT status FROM report WHERE id=?",Integer.class,later));}finally{reset(notifications);}
        resolver.resolveCase(decision);assertTrue(context.getBean(PostMapper.class).selectById(id).getAdminHidden());assertEquals("UPHELD_HIDDEN",db.queryForObject("SELECT outcome FROM report WHERE id=?",String.class,later));assertEquals(2,context.getBean(cn.jualn.miniapp.module.report.service.ReportResolutionFacts.class).history("post",id).size());
    }
    @Test void likesAndObservableSharesDeduplicateWithoutProducingOrdinaryLikeNotifications() {
        long id=create("interactions");passed(id);UserContext.setUserId(99102L);
        var notifications=context.getBean(cn.jualn.miniapp.module.notify.service.NotifyService.class);clearInvocations(notifications);
        var likes=context.getBean(InteractionLikes.class);likes.like(TargetType.POST,id);likes.like(TargetType.POST,id);assertEquals(1,context.getBean(InteractionCounts.class).getLikeCount(TargetType.POST,id));verifyNoInteractions(notifications);
        var shares=context.getBean(InteractionShares.class);shares.share(TargetType.POST,id,1,"WECHAT_SHARE_INVOKED","share-event");shares.share(TargetType.POST,id,1,"WECHAT_SHARE_INVOKED","share-event");assertEquals(1,context.getBean(InteractionCounts.class).getShareCount(TargetType.POST,id));
        assertThrows(ContractProblemException.class,()->shares.share(TargetType.POST,id,null,"POSTER_SAVED","share-event"));
        db.update("UPDATE post SET admin_hidden=TRUE WHERE id=?",id);likes.unlike(TargetType.POST,id);assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM like_record WHERE target_type=1 AND target_id=?",Integer.class,id));
    }
    @Test @EnabledIfSystemProperty(named="community.test.redisContainer",matches="codex-post-community-redis-1031")
    void dailyViewFactsAndReadsSurviveOwnedRedisOutage() throws Exception {
        long id=create("redis independent");passed(id);
        assertEquals(0,new ProcessBuilder("docker","pause",System.getProperty("community.test.redisContainer")).start().waitFor());
        try {context.getBean(InteractionViews.class).view(TargetType.POST,id);context.getBean(InteractionViews.class).view(TargetType.POST,id);assertEquals(1,context.getBean(InteractionCounts.class).getViewCount(TargetType.POST,id));}
        finally {assertEquals(0,new ProcessBuilder("docker","unpause",System.getProperty("community.test.redisContainer")).start().waitFor());}
        context.getBean(InteractionViews.class).view(TargetType.POST,id);assertEquals(1,context.getBean(InteractionCounts.class).getViewCount(TargetType.POST,id));
    }
    @Test void migratedCatalogMatchesTheArtifactAndDoesNotEnableLegacyRestore() {
        var catalog=new cn.jualn.miniapp.module.authorization.service.RbacCatalog();
        var mapper=org.springframework.jdbc.core.BeanPropertyRowMapper.newInstance(cn.jualn.miniapp.module.authorization.model.RbacModels.Definition.class);
        var permissions=db.query("SELECT code,namespaces,lifecycle,readiness,grantable,unavailable_reason,catalog_digest FROM rbac_permission_definition",mapper);
        var roles=db.query("SELECT code,namespaces,lifecycle,readiness,grantable,unavailable_reason,catalog_digest FROM rbac_role",mapper);
        var members=db.query("SELECT role_code,permission_code FROM rbac_role_permission",(row,index)->new cn.jualn.miniapp.module.authorization.model.RbacModels.Member(row.getString(1),row.getString(2)));
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(catalog,"verify",permissions,roles,members);
        assertEquals("620bcf59e0b93c40460bc503cbdebfb426c6326acdc929bc4448329928b3409e",catalog.digest());
        assertEquals("DISABLED",db.queryForObject("SELECT lifecycle FROM rbac_permission_definition WHERE code='post:restore'",String.class));
        assertEquals(0,db.queryForObject("SELECT grantable FROM rbac_permission_definition WHERE code='post:restore'",Integer.class));
    }
    @Test void currentAccountLockReturnsTheStableRestrictionProblem() {
        var actual=new UserAccountService(context.getBean(cn.jualn.miniapp.module.user.mapper.UserProfileMapper.class),context.getBean(cn.jualn.miniapp.infrastructure.cache.RedisService.class),mock(cn.jualn.miniapp.module.authorization.service.RbacGovernanceService.class),context.getBean(cn.jualn.miniapp.module.authorization.service.CurrentAuthorization.class),context.getBean(cn.jualn.miniapp.module.admin.operation.service.AdminAuditJournal.class));
        db.update("UPDATE user_profile SET status=2,ban_expire_at=NULL WHERE id=99101");
        try {
            var error=assertThrows(ContractProblemException.class,()->new TransactionTemplate(context.getBean(DataSourceTransactionManager.class)).execute(tx->{actual.lockCommunityWriter(99101);return null;}));
            assertEquals("/problems/account-content-restricted",error.getType());assertEquals("MUTED",error.getRestriction());assertEquals("MUTED",new cn.jualn.miniapp.common.exception.GlobalExceptionHandler().handleContractProblem(error).getBody().getProperties().get("restriction"));
        } finally {db.update("UPDATE user_profile SET status=1 WHERE id=99101");}
    }
    @Test void removingRootDoesNotCancelTheSafetyRoundOfAnAcceptedCommunityReply() {
        long post=create("pending continuity");passed(post);var writer=context.getBean(CommentService.class);
        long root=writer.createComment(comment(post,null,null,"root"));db.update("UPDATE comment SET status=1,audit_status=1 WHERE id=?",root);
        long current=writer.createComment(comment(post,root,root,"pending current reply"));
        long legacy=writer.createComment(comment(post,root,root,"pending legacy scope"));db.update("UPDATE comment SET community_thread=FALSE WHERE id=?",legacy);
        long currentRound=round(current),legacyRound=round(legacy);
        writer.removeComment(root);
        new TransactionTemplate(context.getBean(DataSourceTransactionManager.class)).execute(tx->{new ContentSafetyInvalidation(context.getBean(ContentSafetyReviewMapper.class)).invalidateTarget(4,root,"target-deleted");return null;});
        assertEquals("checking",db.queryForObject("SELECT state FROM content_safety_review WHERE task_id=?",String.class,currentRound));
        assertEquals("invalidated",db.queryForObject("SELECT state FROM content_safety_review WHERE task_id=?",String.class,legacyRound));
        new TransactionTemplate(context.getBean(DataSourceTransactionManager.class)).execute(tx->{context.getBean(CommentAuditCallback.class).activateCommentAfterAuditPass(current);return null;});
        assertEquals("AVAILABLE",context.getBean(CommunityCommentService.class).context(post,current).availability());
    }
    private long round(long comment) {
        var log=cn.jualn.miniapp.module.audit.entity.ContentAuditLog.builder().targetType(4).targetId(comment).auditSource(1).finalResult(0).build();context.getBean(ContentAuditLogMapper.class).insert(log);
        var r=new cn.jualn.miniapp.module.audit.entity.ContentSafetyReview();r.setTaskId(log.getId());r.setTargetType(4);r.setTargetId(comment);r.setSubmissionVersion(java.util.UUID.randomUUID().toString());r.setScene("post-comment");r.setResourceNamespace("POST");r.setBusinessRevision(0L);r.setSnapshotTitle("comment");r.setSnapshotContent("pending");r.setSnapshotAttachments("[]");r.setExpectedChecks(1);r.setState("checking");r.setCreatedAt(PostCommunityService.now());context.getBean(ContentSafetyReviewMapper.class).insert(r);return log.getId();
    }
    @Test void staleLegacySearchDocumentsCannotExposeHiddenOrExpiredPosts() {
        long id=create("sentinelcommunity");passed(id);var search=context.getBean(cn.jualn.miniapp.module.search.mapper.SearchDocMapper.class);search.upsert(1,id,"sentinelcommunity",1,PostCommunityService.now());
        assertEquals(1,search.selectSearchResults("sentinelcommunity",1,null,null,20).size());
        db.update("UPDATE post SET admin_hidden=TRUE WHERE id=?",id);assertTrue(search.selectSearchResults("sentinelcommunity",1,null,null,20).isEmpty());assertTrue(search.selectTabCounts("sentinelcommunity").isEmpty());
        db.update("UPDATE post SET admin_hidden=FALSE,audit_status=0,temporary_visible_until=DATE_SUB(NOW(),INTERVAL 1 SECOND) WHERE id=?",id);assertTrue(search.selectSearchResults("sentinelcommunity",1,null,null,20).isEmpty());
    }
    @Test void reportNotificationNavigationSurvivesTheHttpConverter() {
        var target=new cn.jualn.miniapp.module.notify.bo.NotificationCenterBO.Target("MY_REPORT_DETAIL",null,null,null,null,null,null,null,"report-9");
        var item=new cn.jualn.miniapp.module.notify.bo.NotificationCenterBO.Item("notification-9","SYSTEM","POST_REPORT_RESOLVED",new cn.jualn.miniapp.module.notify.bo.NotificationCenterBO.Presentation("处理结果","请查看本人举报",null,null,null),null,null,target,null,"2026-10-31T10:00:00+08:00");
        var converter=org.mapstruct.factory.Mappers.getMapper(cn.jualn.miniapp.module.notify.converter.NotificationCenterConverter.class);
        assertEquals("report-9",converter.item(item).target().reportId());
    }
    private long question(String text) {
        db.update("INSERT IGNORE INTO post_category(id,code,name) VALUES(99110,'QA_TEST','qa')");db.update("INSERT IGNORE INTO post_category_type(category_id,post_type) VALUES(99110,'MENTOR_QA')");
        return community.create(PostCreateBO.builder().postType(PostType.MENTOR_QA).categoryId(99110L).content(text).build(),null).id();
    }
    private void mentor(long user,String person) {
        var proof=new cn.jualn.miniapp.module.post.bo.MentorQaBO.Proof(user,person,"binding-"+person,"qualification-"+person,"1",java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC),new cn.jualn.miniapp.module.post.bo.MentorQaBO.Profile("Original mentor","position","unit"));
        var d=new MentorQualification.Decision(MentorQualification.State.VALID,"{\"personId\":\""+person+"\"}",proof);
        when(context.getBean(MentorQualification.class).evaluate(user)).thenReturn(d);when(context.getBean(MentorQualification.class).forWrite(user)).thenReturn(d);
    }
    private long answer(long question,String version) {var c=comment(question,null,null,"formal answer");c.setQuestionVersion(version);return context.getBean(CommentService.class).createComment(c);}
    private void publish(long id) {new TransactionTemplate(context.getBean(DataSourceTransactionManager.class)).execute(tx->{context.getBean(CommentAuditCallback.class).activateCommentAfterAuditPass(id);return null;});}
    @Test void qaOrdinaryQuestionAndDiscussionDoNotNeedPersonAndFormalAnswerFreezesContent() {
        long id=question("question text");var summary=context.getBean(MentorQuestions.class).summary(id);assertEquals("UNANSWERED",summary.answerState());
        var edits=context.getBean(PostEditingService.class);var before=edits.get(id);
        UserContext.setUserId(99102L);mentor(99102,"original-person");long answer=answer(id,summary.questionVersion());
        assertEquals(0,context.getBean(MentorQuestions.class).summary(id).publicAnswerCount());publish(answer);assertEquals(1,context.getBean(MentorQuestions.class).summary(id).publicAnswerCount());
        UserContext.setUserId(99101L);assertEquals("/problems/mentor-question-content-locked",assertThrows(ContractProblemException.class,()->edits.edit(id,new PostEditBO(null,"changed",null,null,null,Set.of("content")),before.etag())).getType());
        edits.edit(id,new PostEditBO(null,null,null,List.of(),null,Set.of("topicIds")),before.etag());assertEquals(summary.questionVersion(),context.getBean(MentorQuestions.class).summary(id).questionVersion());
        long discussion=context.getBean(CommentService.class).createComment(comment(id,answer,answer,"ordinary discussion"));publish(discussion);
        var root=(cn.jualn.miniapp.module.comment.bo.CommunityCommentBO.VisibleRoot)context.getBean(CommunityCommentService.class).roots(id,null,20).getList().get(0);assertEquals("MENTOR_ANSWER",root.kind());assertEquals("Original mentor",root.mentorAnswer().profileAtAnswer().realName());assertFalse(root.mentorAnswer().firstPublishedAt().toInstant().isBefore(root.mentorAnswer().qualifiedAt().toInstant()));
        assertEquals("DISCUSSION",context.getBean(CommunityCommentService.class).replies(id,answer,null,20,null).getList().get(0).kind());
        when(context.getBean(MentorQualification.class).evaluate(99102)).thenReturn(new MentorQualification.Decision(MentorQualification.State.INELIGIBLE,null));
        assertEquals("INACTIVE",context.getBean(MentorAnswers.class).attribution(answer).currentCertification().state());assertEquals(1,context.getBean(MentorQuestions.class).summary(id).publicAnswerCount());
        UserContext.setUserId(99102L);context.getBean(CommentService.class).removeComment(answer);assertEquals(0,context.getBean(MentorQuestions.class).summary(id).publicAnswerCount());assertTrue(context.getBean(MentorQuestions.class).summary(id).contentLocked());assertTrue(context.getBean(CommunityCommentService.class).roots(id,null,20).getList().get(0) instanceof cn.jualn.miniapp.module.comment.bo.CommunityCommentBO.PlaceholderRoot);
    }
    @Test void qaCompleteLegacyCandidateKeepsOriginalProofAtFirstPublication() {
        long id=question("legacy proof candidate");String version=context.getBean(MentorQuestions.class).summary(id).questionVersion();
        UserContext.setUserId(99102L);mentor(99102,"legacy-person");long candidate=answer(id,version);
        String full=db.queryForObject("SELECT CAST(mentor_attribution AS CHAR) FROM comment WHERE id=?",String.class,candidate);assertTrue(full.length()<=512);
        db.update("UPDATE comment SET mentor_evidence=mentor_attribution,mentor_attribution=NULL WHERE id=?",candidate);
        publish(candidate);assertNotNull(db.queryForObject("SELECT first_published_at FROM comment WHERE id=?",java.time.LocalDateTime.class,candidate));
        assertEquals(1,context.getBean(MentorQuestions.class).summary(id).publicAnswerCount());
        assertEquals("Original mentor",context.getBean(MentorAnswers.class).attribution(candidate).profileAtAnswer().realName());
    }
    @Test void qaVersionChangeAndQualificationRevocationCannotPublishOldCandidate() {
        long id=question("editable question");var questions=context.getBean(MentorQuestions.class);String version=questions.summary(id).questionVersion();
        UserContext.setUserId(99102L);mentor(99102,"candidate-person");long a=answer(id,version);
        UserContext.setUserId(99101L);var edits=context.getBean(PostEditingService.class);edits.edit(id,new PostEditBO(null,"new question",null,null,null,Set.of("content")),edits.get(id).etag());
        assertNotEquals(version,questions.summary(id).questionVersion());publish(a);assertEquals(0,questions.summary(id).publicAnswerCount());assertEquals("QUESTION_CHANGED",db.queryForObject("SELECT submission_notice FROM comment WHERE id=?",String.class,a));
        UserContext.setUserId(99102L);assertThrows(ContractProblemException.class,()->answer(id,version));long b=answer(id,questions.summary(id).questionVersion());
        when(context.getBean(MentorQualification.class).forWrite(99102)).thenReturn(new MentorQualification.Decision(MentorQualification.State.INELIGIBLE,null));publish(b);assertEquals(0,questions.summary(id).publicAnswerCount());assertFalse(questions.summary(id).contentLocked());
    }
    @Test void qaPublicationAndQuestionEditRaceHaveOneConsistentWinner() throws Exception {
        long id=question("race original");String version=context.getBean(MentorQuestions.class).summary(id).questionVersion();UserContext.setUserId(99102L);mentor(99102,"race-person");long candidate=answer(id,version);
        UserContext.setUserId(99101L);var edits=context.getBean(PostEditingService.class);String tag=edits.get(id).etag();var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try {
            var edit=pool.submit(()->{UserContext.setUserId(99101L);try{start.await();edits.edit(id,new PostEditBO(null,"race changed",null,null,null,Set.of("content")),tag);return true;}catch(ContractProblemException e){assertEquals("/problems/mentor-question-content-locked",e.getType());return false;}finally{UserContext.clear();}});
            var publication=pool.submit(()->{UserContext.setUserId(99102L);try{start.await();publish(candidate);return true;}finally{UserContext.clear();}});start.countDown();boolean edited=edit.get(20,TimeUnit.SECONDS);publication.get(20,TimeUnit.SECONDS);
            int status=db.queryForObject("SELECT status FROM comment WHERE id=?",Integer.class,candidate);String content=db.queryForObject("SELECT content FROM post WHERE id=?",String.class,id);
            if(edited){assertNotEquals(1,status);assertEquals("race changed",content);}else{assertEquals(1,status);assertEquals("race original",content);}
        } finally {pool.shutdownNow();}
    }
    @Test void qaPublishedAnswerRestorationUsesOriginalEvidenceAndDoesNotRepeatFirstNotification() {
        long id=question("historical answer");String version=context.getBean(MentorQuestions.class).summary(id).questionVersion();UserContext.setUserId(99102L);mentor(99102,"historic-person");long a=answer(id,version);
        var notify=context.getBean(cn.jualn.miniapp.module.notify.service.NotifyService.class);clearInvocations(notify);publish(a);publish(a);
        var disposition=cn.jualn.miniapp.module.comment.bo.AdminCommentActionBO.builder().operatorId(99101L).commentId(a).reason("proven hide and correction").build();var governance=context.getBean(CommentGovernanceService.class);governance.takeDownComment(disposition);
        when(context.getBean(MentorQualification.class).forWrite(99102)).thenReturn(new MentorQualification.Decision(MentorQualification.State.INELIGIBLE,null));when(context.getBean(MentorQualification.class).evaluate(99102)).thenReturn(new MentorQualification.Decision(MentorQualification.State.INELIGIBLE,null));
        governance.restoreComment(disposition);assertEquals(1,context.getBean(MentorQuestions.class).summary(id).publicAnswerCount());assertEquals("INACTIVE",context.getBean(MentorAnswers.class).attribution(a).currentCertification().state());
        var calls=org.mockito.Mockito.mockingDetails(notify).getInvocations().stream().filter(i->i.getMethod().getName().equals("processNotificationPayload")).map(i->(cn.jualn.miniapp.module.notify.payload.NotifyPayload)i.getArgument(0)).filter(x->x.getType()==cn.jualn.miniapp.module.notify.enums.NotifyType.POST_COMMENTED).count();assertEquals(1,calls);
    }
    @Test void qaAnswerFilteringIsBeforePaginationAndBoundToTheCursor() {
        long answered=question("filter answered"),unanswered=question("filter unanswered");UserContext.setUserId(99102L);mentor(99102,"filter-person");long a=answer(answered,context.getBean(MentorQuestions.class).summary(answered).questionVersion());publish(a);UserContext.setUserId(99101L);
        var queries=context.getBean(PostQueries.class);var q=new PostFeedQueryBO();q.setPostType("MENTOR_QA");q.setAnswerState("ANSWERED");q.setPageSize(1);var first=(cn.jualn.miniapp.module.post.bo.PostFeedPageBO)queries.pagePost(q);assertEquals(answered,first.getList().get(0).getId());
        if(first.getCursor()!=null){q.setCursor(first.getCursor());q.setAnswerState("UNANSWERED");assertThrows(ContractProblemException.class,()->queries.pagePost(q));}
        q.setCursor(null);q.setAnswerState("UNANSWERED");assertEquals(unanswered,queries.pagePost(q).getList().get(0).getId());
        q.setPostType("NORMAL");assertThrows(ContractProblemException.class,()->queries.pagePost(q));
        assertTrue(community.mine(null,50).items().stream().allMatch(x->"NORMAL".equals(x.post().getPostType())));assertTrue(community.mine(null,50,"MENTOR_QA").items().stream().anyMatch(x->x.post().getId()==answered));
    }
    @Test void qaNonMentorCannotForgeRootAndUnknownHistoryCannotBecomeZeroAnswers() {
        long id=question("source boundaries");String version=context.getBean(MentorQuestions.class).summary(id).questionVersion();
        var irrelevant=comment(id,1L,1L,"ordinary");irrelevant.setQuestionVersion(version);
        assertEquals(400,assertThrows(ContractProblemException.class,()->context.getBean(CommentService.class).createComment(irrelevant)).getStatus().value());
        assertThrows(Exception.class,()->context.getBean(com.fasterxml.jackson.databind.ObjectMapper.class).readValue("{\"content\":\"forged\",\"personId\":\"forged\"}",cn.jualn.miniapp.module.comment.dto.request.CommentCreateRequest.class));
        var qualifications=context.getBean(MentorQualification.class);
        when(qualifications.forWrite(99101)).thenReturn(new MentorQualification.Decision(MentorQualification.State.INELIGIBLE,null));
        assertEquals("/problems/mentor-answer-forbidden",assertThrows(ContractProblemException.class,()->answer(id,version)).getType());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM comment WHERE target_type=1 AND target_id=?",Integer.class,id));
        when(qualifications.forWrite(99101)).thenReturn(new MentorQualification.Decision(MentorQualification.State.UNAVAILABLE,null));
        assertEquals(503,assertThrows(ContractProblemException.class,()->answer(id,version)).getStatus().value());
        db.update("UPDATE post SET question_version=NULL WHERE id=?",id);
        assertEquals(503,assertThrows(ContractProblemException.class,()->context.getBean(MentorQuestions.class).summary(id)).getStatus().value());
        db.update("UPDATE post SET question_version=? WHERE id=?",version,id);
    }
    @Test void qaConcurrentMentorsCountOnceEachAndCurrentCertificationDoesNotRewriteHistory() throws Exception {
        long id=question("multiple mentors");String version=context.getBean(MentorQuestions.class).summary(id).questionVersion();
        mentor(99101,"first-person");long first=answer(id,version);UserContext.setUserId(99102L);mentor(99102,"second-person");long second=answer(id,version);
        var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try {var a=pool.submit(()->{start.await();publish(first);return true;});var b=pool.submit(()->{start.await();publish(second);return true;});start.countDown();a.get(20,TimeUnit.SECONDS);b.get(20,TimeUnit.SECONDS);} finally{pool.shutdownNow();}
        publish(first);publish(second);assertEquals(2,context.getBean(MentorQuestions.class).summary(id).publicAnswerCount());
        mentor(99102,"rebound-person");assertEquals("INACTIVE",context.getBean(MentorAnswers.class).attribution(second).currentCertification().state());
        when(context.getBean(MentorQualification.class).evaluate(99102)).thenReturn(new MentorQualification.Decision(MentorQualification.State.UNAVAILABLE,null));
        var historical=context.getBean(MentorAnswers.class).attribution(second);assertEquals("UNAVAILABLE",historical.currentCertification().state());assertEquals("Original mentor",historical.profileAtAnswer().realName());
        assertEquals(2,context.getBean(MentorQuestions.class).summary(id).publicAnswerCount());
        db.update("UPDATE comment SET question_version='unproven-version' WHERE id=?",second);
        assertEquals(503,assertThrows(ContractProblemException.class,()->context.getBean(MentorQuestions.class).summary(id)).getStatus().value());
        db.update("UPDATE comment SET question_version=? WHERE id=?",version,second);
        db.update("UPDATE post SET admin_hidden=TRUE,question_content_locked=FALSE WHERE id=?",id);
        assertEquals(503,assertThrows(ContractProblemException.class,()->context.getBean(MentorQuestions.class).summary(id)).getStatus().value());
        db.update("UPDATE post SET admin_hidden=FALSE,question_content_locked=TRUE WHERE id=?",id);
    }
    @Test void qaRealQualificationPolicyCapturesOriginalProfileAndRollsBackWhenSourceExpiresAtCommit() {
        var facts=mock(cn.jualn.miniapp.module.person.mapper.PersonMapper.class);var profiles=mock(cn.jualn.miniapp.module.person.mapper.ManagedPersonMapper.class);var qualifications=mock(cn.jualn.miniapp.module.person.mapper.QualificationMapper.class);var accounts=mock(cn.jualn.miniapp.module.authorization.service.TransactionalAuthorization.class);
        var at=java.time.LocalDateTime.now(java.time.ZoneOffset.UTC);when(facts.now()).thenReturn(at);
        var binding=new cn.jualn.miniapp.module.person.entity.ManagedPersonRows.Binding();binding.setId("trusted-binding");binding.setPersonId("original-policy-person");binding.setUserId(99102);binding.setStatus("ACTIVE");binding.setVerificationMethod("ADMIN_MANUAL");binding.setSourceReference("approved evidence");when(profiles.accountBinding(99102)).thenReturn(binding);when(profiles.confirmedAdminBinding("trusted-binding")).thenReturn(1L);
        var profile=new cn.jualn.miniapp.module.person.entity.ManagedPersonRows.Profile();profile.setId(binding.getPersonId());profile.setStatus("ACTIVE");profile.setRealName("Original source name");when(profiles.profile(binding.getPersonId())).thenReturn(profile);
        var mentor=new cn.jualn.miniapp.module.person.entity.QualificationRows.Mentor();mentor.setPersonId(binding.getPersonId());mentor.setRealName(profile.getRealName());mentor.setPosition("position");mentor.setAffiliation("unit");mentor.setQualificationId("trusted-qualification");mentor.setQualificationState("APPROVED");mentor.setValidFrom(at.minusDays(1));mentor.setValidUntil(at.plusDays(1));mentor.setVerifiedAt(at.minusDays(1));mentor.setVerifiedBy(99101L);mentor.setVerificationReference("approved ref");when(qualifications.mentor(binding.getPersonId())).thenReturn(mentor);
        when(accounts.current(eq(99102L),any())).thenReturn(new cn.jualn.miniapp.module.authorization.model.RbacModels.Decision(99102,cn.jualn.miniapp.module.authorization.model.RbacModels.Source.LEGACY,"test","test",at,true,List.of(),List.of()));
        var actual=new cn.jualn.miniapp.module.person.service.qualification.PersistentMentorQualification(qualifications,facts,profiles,accounts);long id=question("source rollback");
        assertThrows(ContractProblemException.class,()->new TransactionTemplate(context.getBean(DataSourceTransactionManager.class)).execute(tx->{var d=actual.forWrite(99102);assertTrue(d.valid());assertEquals("Original source name",d.proof().profileAtAnswer().realName());new JdbcTemplate(context.getBean(DataSource.class)).update("UPDATE post SET question_content_locked=TRUE WHERE id=?",id);mentor.setValidUntil(at);return null;}));
        assertFalse(context.getBean(MentorQuestions.class).summary(id).contentLocked());mentor.setValidUntil(at.plusDays(1));binding.setVerificationMethod("SELF_CLAIM");
        new TransactionTemplate(context.getBean(DataSourceTransactionManager.class)).execute(tx->{assertEquals(MentorQualification.State.UNAVAILABLE,actual.forWrite(99102).state());return null;});
        binding.setVerificationMethod("ADMIN_MANUAL");when(facts.corruptSource()).thenReturn(1L);new TransactionTemplate(context.getBean(DataSourceTransactionManager.class)).execute(tx->{assertEquals(MentorQualification.State.UNAVAILABLE,actual.forWrite(99102).state());return null;});
    }
    @Test void httpQaProjectionsContainVersionIdentityAndOrdinaryDiscussionKind() throws Exception {
        question("dictionary seed");var json=context.getBean(com.fasterxml.jackson.databind.ObjectMapper.class);
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
            new cn.jualn.miniapp.module.post.controller.PostController(context.getBean(PostService.class),community,context.getBean(PostQueries.class),context.getBean(TestMediaService.class),context.getBean(cn.jualn.miniapp.module.post.converter.PostConverter.class),context.getBean(PostEditingService.class),org.mockito.Mockito.mock(cn.jualn.miniapp.module.post.service.GrowthRecordAccess.class),org.mockito.Mockito.mock(cn.jualn.miniapp.module.post.service.GrowthRecordService.class)),
            new cn.jualn.miniapp.module.post.controller.PersonalPostController(community),new cn.jualn.miniapp.module.comment.controller.CommentController(context.getBean(CommentService.class),context.getBean(CommunityCommentService.class),context.getBean(PostPublicAccess.class),mock(CommentQueries.class),context.getBean(TestMediaService.class),org.mapstruct.factory.Mappers.getMapper(cn.jualn.miniapp.module.comment.converter.CommentConverter.class)),new cn.jualn.miniapp.module.comment.controller.PersonalCommentController(context.getBean(CommunityCommentService.class)))
            .setControllerAdvice(new cn.jualn.miniapp.common.exception.GlobalExceptionHandler()).setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(json)).build();
        var created=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/v1/post").contentType("application/json").content("{\"postType\":\"MENTOR_QA\",\"categoryId\":\"99110\",\"content\":\"HTTP question\"}")).andReturn();capture("PostResult",created);
        var body=json.readTree(created.getResponse().getContentAsString()).get("data");long id=body.get("id").asLong();String version=body.get("qaSummary").get("questionVersion").asText();
        capture("MyPost",mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/v1/users/me/posts/"+id)).andReturn());capture("MyPostPage",mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/v1/users/me/posts").param("postType","MENTOR_QA")).andReturn());
        UserContext.setUserId(99102L);mentor(99102,"http-original-person");var accepted=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/v1/comment").contentType("application/json").content("{\"targetType\":\"POST\",\"targetId\":\""+id+"\",\"questionVersion\":\""+version+"\",\"content\":\"HTTP formal answer\"}")).andReturn();capture("PostCommentIdResult",accepted);long answer=json.readTree(accepted.getResponse().getContentAsString()).get("data").asLong();
        capture("MyPostComment",mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/v1/users/me/post-comments/"+answer)).andReturn());publish(answer);
        capture("PostThreadResult",mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/v1/comment").param("targetType","POST").param("targetId",Long.toString(id)).param("representation","thread")).andReturn());
        capture("PostCommentContext",mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/v1/comment/"+answer+"/context").param("postId",Long.toString(id))).andReturn());
        capture("PostResult",mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/v1/post/"+id)).andReturn());
    }
    private long privateFile(long post,String key) {
        var file=new cn.jualn.miniapp.module.media.entity.MediaUploadRecord();file.setObjectKey(key);file.setUserId(99101L);file.setTargetType(1);file.setStatus(1);file.setBoundTargetId(post);file.setCleanupAfter(PostCommunityService.now().plusHours(1));file.setRetryCount(0);file.setDeclaredKind("VERIFIED");file.setDeclaredContentType("image/png");file.setDeclaredSizeBytes(6L);
        context.getBean(cn.jualn.miniapp.module.media.mapper.MediaUploadRecordMapper.class).insert(file);
        db.update("INSERT INTO media_attachment(target_type,target_id,type,kind,object_key,url,sort_order) VALUES(1,?,2,'IMAGE',?,'https://api.example/controlled',0)",post,key);return file.getId();
    }
    @Test void controlledMediaRevokesEveryNewReadAndDoesNotSharePrivatePreviews() throws Exception {
        long post=create("controlled image");passed(post);String key="private/media-effective/1/99101/"+UUID.randomUUID();long file=privateFile(post,key);
        var content=context.getBean(cn.jualn.miniapp.module.media.service.MediaContentService.class);
        assertNotNull(content.require(file,null,false));
        db.update("UPDATE post SET admin_hidden=TRUE WHERE id=?",post);
        assertEquals(404,assertThrows(ContractProblemException.class,()->content.require(file,null,false)).getStatus().value());
        assertNotNull(content.require(file,99101L,false));assertThrows(ContractProblemException.class,()->content.require(file,99102L,false));
        db.update("UPDATE post SET admin_hidden=FALSE WHERE id=?",post);
        db.update("DELETE FROM media_attachment WHERE object_key=?",key);assertThrows(ContractProblemException.class,()->content.require(file,null,false));
        db.update("UPDATE media_upload_record SET status=0,bound_target_id=NULL WHERE id=?",file);assertNotNull(content.require(file,99101L,false));
        assertThrows(ContractProblemException.class,()->content.require(file,null,false));
        db.update("UPDATE media_upload_record SET cleanup_started_at=NOW(6) WHERE id=?",file);assertThrows(ContractProblemException.class,()->content.require(file,99101L,false));
    }
    @Test void controlledCommentImageChecksNodeRootAndSubjectInsteadOfOnlyFileOwnership() {
        long post=create("comment image");passed(post);var writer=context.getBean(CommentService.class);long root=writer.createComment(comment(post,null,null,"root"));publish(root);UserContext.setUserId(99102L);long reply=writer.createComment(comment(post,root,root,"image reply"));publish(reply);
        String key="private/media-effective/4/99102/"+UUID.randomUUID();long file=privateFile(post,key);
        db.update("DELETE FROM media_attachment WHERE object_key=?",key);db.update("UPDATE media_upload_record SET user_id=99102,target_type=4,bound_target_id=? WHERE id=?",reply,file);db.update("UPDATE comment SET image_object_key=?,image_url='https://api.example/controlled' WHERE id=?",key,reply);
        var content=context.getBean(cn.jualn.miniapp.module.media.service.MediaContentService.class);assertNotNull(content.require(file,null,false));
        UserContext.setUserId(99101L);writer.removeComment(root);assertNotNull(content.require(file,null,false));
        db.update("UPDATE comment SET status=4 WHERE id=?",reply);assertThrows(ContractProblemException.class,()->content.require(file,null,false));
        assertNotNull(content.require(file,99102L,false));assertThrows(ContractProblemException.class,()->content.require(file,99101L,false));
        db.update("UPDATE post SET admin_hidden=TRUE WHERE id=?",post);assertThrows(RuntimeException.class,()->content.require(file,99102L,false));
    }
    @Test void controlledMediaStreamsBoundedRangesAndHeadWithoutProviderOrSharedCache() throws Exception {
        long post=create("range image");passed(post);String key="private/media-effective/1/99101/"+UUID.randomUUID();long file=privateFile(post,key);
        var cos=mock(cn.jualn.miniapp.third.cos.client.CosClient.class);var closed=new java.util.concurrent.atomic.AtomicBoolean();
        when(cos.openObject(eq(key),anyLong(),anyLong())).thenAnswer(i->{long start=i.getArgument(1),end=i.getArgument(2);var object=new com.qcloud.cos.model.COSObject();var metadata=new com.qcloud.cos.model.ObjectMetadata();metadata.setContentLength(end-start+1);object.setObjectMetadata(metadata);object.setObjectContent(new java.io.ByteArrayInputStream(java.util.Arrays.copyOfRange(new byte[]{0,1,2,3,4,5},(int)start,(int)end+1)){public void close()throws java.io.IOException{closed.set(true);super.close();}});return object;});
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new cn.jualn.miniapp.module.media.controller.MediaContentController(context.getBean(cn.jualn.miniapp.module.media.service.MediaContentService.class),cos)).setControllerAdvice(new cn.jualn.miniapp.common.exception.GlobalExceptionHandler()).build();
        String url="/v1/media/files/"+file+"/content";
        var part=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url).header("Range","bytes=1-3")).andReturn().getResponse();assertEquals(206,part.getStatus());assertArrayEquals(new byte[]{1,2,3},part.getContentAsByteArray());assertEquals("bytes 1-3/6",part.getHeader("Content-Range"));assertEquals("no-store",part.getHeader("Cache-Control"));assertTrue(closed.get());
        clearInvocations(cos);assertEquals(200,mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head(url)).andReturn().getResponse().getStatus());verifyNoInteractions(cos);
        assertEquals(416,mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url).header("Range","bytes=8-9")).andReturn().getResponse().getStatus());verifyNoInteractions(cos);
        db.update("UPDATE post SET deleted_at=NOW(6) WHERE id=?",post);assertEquals(404,mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url).header("Range","bytes=1-3")).andReturn().getResponse().getStatus());verifyNoInteractions(cos);
        db.update("DELETE FROM media_attachment WHERE object_key=?",key);
    }
    @Test void httpRepresentationsMatchTheCurrentContract() throws Exception {
        var json=context.getBean(com.fasterxml.jackson.databind.ObjectMapper.class);
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
            new cn.jualn.miniapp.module.post.controller.PostController(context.getBean(PostService.class),community,mock(PostQueries.class),context.getBean(TestMediaService.class),org.mapstruct.factory.Mappers.getMapper(cn.jualn.miniapp.module.post.converter.PostConverter.class),context.getBean(PostEditingService.class),org.mockito.Mockito.mock(cn.jualn.miniapp.module.post.service.GrowthRecordAccess.class),org.mockito.Mockito.mock(cn.jualn.miniapp.module.post.service.GrowthRecordService.class)),
            new cn.jualn.miniapp.module.post.controller.PersonalPostController(community),
            new cn.jualn.miniapp.module.comment.controller.CommentController(context.getBean(CommentService.class),context.getBean(CommunityCommentService.class),context.getBean(PostPublicAccess.class),mock(CommentQueries.class),context.getBean(TestMediaService.class),org.mapstruct.factory.Mappers.getMapper(cn.jualn.miniapp.module.comment.converter.CommentConverter.class)),
            new cn.jualn.miniapp.module.comment.controller.PersonalCommentController(context.getBean(CommunityCommentService.class)),new cn.jualn.miniapp.module.interact.controller.InteractController(context.getBean(InteractionCounts.class),context.getBean(InteractionLikeQueries.class),context.getBean(InteractionLikes.class),context.getBean(InteractionShares.class),context.getBean(InteractionViews.class)))
            .setControllerAdvice(new cn.jualn.miniapp.common.exception.GlobalExceptionHandler()).setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(json)).build();
        var created=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/v1/post").header("Idempotency-Key","http-intent").contentType("application/json").content("{\"content\":\"http body\"}")).andReturn();
        capture("PostResult",created);long id=json.readTree(created.getResponse().getContentAsString()).get("data").get("id").asLong();
        capture("MyPost",mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/v1/users/me/posts/"+id)).andReturn());
        capture("MyPostPage",mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/v1/users/me/posts")).andReturn());
        capture("PostSubmission",mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/v1/users/me/posts/creation-receipt").param("key","http-intent")).andReturn());
        capture("PostFavoriteState",mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/v1/post/"+id+"/favorite")).andReturn());
        capture("PostFavoritePage",mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/v1/users/me/post-favorites")).andReturn());
        context.getBean(InteractionViews.class).view(TargetType.POST,id);
        capture("PostInteractionCountResult",mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/v1/interact/view/count").param("targetType","POST").param("targetId",Long.toString(id))).andReturn());
        capture("PostViewHistoryPage",mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/v1/users/me/post-view-history")).andReturn());
        var rootResult=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/v1/comment").header("Idempotency-Key","http-comment").contentType("application/json").content("{\"targetType\":\"POST\",\"targetId\":\""+id+"\",\"content\":\"comment\"}")).andReturn();
        capture("PostCommentIdResult",rootResult);long root=json.readTree(rootResult.getResponse().getContentAsString()).get("data").asLong();
        capture("MyPostComment",mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/v1/users/me/post-comments/"+root)).andReturn());
        capture("MyPostCommentPage",mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/v1/users/me/post-comments")).andReturn());
        capture("PostCommentSubmission",mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/v1/users/me/post-comments/creation-receipt").param("key","http-comment")).andReturn());
        new TransactionTemplate(context.getBean(DataSourceTransactionManager.class)).execute(tx->{context.getBean(CommentAuditCallback.class).activateCommentAfterAuditPass(root);return null;});
        capture("PostThreadResult",mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/v1/comment").param("targetType","POST").param("targetId",Long.toString(id)).param("representation","thread")).andReturn());
        capture("PostCommentContext",mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/v1/comment/"+root+"/context").param("postId",Long.toString(id))).andReturn());
    }
    static void capture(String schema,org.springframework.test.web.servlet.MvcResult response)throws Exception {
        assertEquals(200,response.getResponse().getStatus(),response.getResponse().getContentAsString());
        captures.add(Map.of("schema",schema,"body",context.getBean(com.fasterxml.jackson.databind.ObjectMapper.class).readTree(response.getResponse().getContentAsString())));
    }

    @Configuration @EnableTransactionManagement
    @Import({cn.jualn.miniapp.module.media.service.MediaContentService.class,PostMediaContentPolicy.class,CommentMediaContentPolicy.class,PostQueries.class,PostCursorCodec.class,MentorQuestions.class,MentorAnswers.class,CommentGovernanceService.class,PostCommunityService.class,PostServiceImpl.class,PostPublicAccess.class,PostEditingService.class,PostRepresentationTags.class,
        CommunityCommentService.class,CommentServiceImpl.class,CommentTargetAccess.class,PostQaCommentPolicy.class,PostCommentPermissions.class,
        CommentCounters.class,CommentAuditCallback.class,PostInteractionSubject.class,CommunitySafetyStates.class,AuditLogService.class,InteractionViews.class,InteractionAccess.class,InteractionCounts.class,InteractionLikeQueries.class,InteractionLikes.class,InteractionShares.class,cn.jualn.miniapp.module.interact.service.impl.LikeCache.class,cn.jualn.miniapp.module.interact.service.impl.LikeNotifications.class,cn.jualn.miniapp.module.post.service.impl.PostNotificationFactsServiceImpl.class,cn.jualn.miniapp.module.comment.service.impl.CommentNotificationFactsServiceImpl.class,PostGovernanceService.class,PostCommunityNotifications.class,PostCategoryMaintenance.class,cn.jualn.miniapp.module.report.service.impl.AdminReportCaseServiceImpl.class,cn.jualn.miniapp.module.report.service.impl.ReportServiceImpl.class,cn.jualn.miniapp.module.report.service.ReportResolutionFacts.class})
    @cn.jualn.miniapp.module.post.OrdinaryGrowthTestConfiguration.Fixture
    static class Config {
        @Bean DataSource source(){var ds=new DriverManagerDataSource(System.getProperty("community.test.jdbcUrl")+"?allowPublicKeyRetrieval=true&sslMode=DISABLED&serverTimezone=Asia/Shanghai","root","");var properties=new java.util.Properties();properties.setProperty("sessionVariables","time_zone='+08:00'");ds.setConnectionProperties(properties);return ds;}
        @Bean DataSourceTransactionManager transactions(DataSource ds){return new DataSourceTransactionManager(ds);}
        @Bean SqlSessionFactory sessions(DataSource ds)throws Exception{var f=new MybatisSqlSessionFactoryBean();f.setDataSource(ds);var c=new MybatisConfiguration();c.setMapUnderscoreToCamelCase(true);f.setConfiguration(c);var g=new com.baomidou.mybatisplus.core.config.GlobalConfig();g.setMetaObjectHandler(new cn.jualn.miniapp.config.MybatisPlusConfig().metaObjectHandler());g.setDbConfig(new com.baomidou.mybatisplus.core.config.GlobalConfig.DbConfig());g.getDbConfig().setLogicNotDeleteValue("NULL");g.getDbConfig().setLogicDeleteValue("NOW()");f.setGlobalConfig(g);f.setMapperLocations(new ClassPathResource("mapper/PostOrganizationMapper.xml"),new ClassPathResource("mapper/MentorAnswerEvidence.xml"), new ClassPathResource("mapper/MentorQuestionMapper.xml"), new ClassPathResource("mapper/PostMapper.xml"),new ClassPathResource("mapper/CommentMapper.xml"),new ClassPathResource("mapper/ReportMapper.xml"),new ClassPathResource("mapper/LikeRecordMapper.xml"),new ClassPathResource("mapper/SearchDocMapper.xml"));return f.getObject();}
        <T> MapperFactoryBean<T> mapper(Class<T> t,SqlSessionFactory s){var f=new MapperFactoryBean<>(t);f.setSqlSessionFactory(s);return f;}
        @Bean MapperFactoryBean<PostMapper> posts(SqlSessionFactory s){return mapper(PostMapper.class,s);}
        @Bean MapperFactoryBean<PostCommunityMapper> community(SqlSessionFactory s){return mapper(PostCommunityMapper.class,s);}
        @Bean MapperFactoryBean<CommentMapper> comments(SqlSessionFactory s){return mapper(CommentMapper.class,s);}
        @Bean MapperFactoryBean<CommunityCommentMapper> communityComments(SqlSessionFactory s){return mapper(CommunityCommentMapper.class,s);}
        @Bean MapperFactoryBean<ContentSafetyReviewMapper> reviews(SqlSessionFactory s){return mapper(ContentSafetyReviewMapper.class,s);}
        @Bean MapperFactoryBean<ContentAuditLogMapper> logs(SqlSessionFactory s){return mapper(ContentAuditLogMapper.class,s);}
        @Bean MapperFactoryBean<PostInteractionMapper> interactions(SqlSessionFactory s){return mapper(PostInteractionMapper.class,s);}
        @Bean com.fasterxml.jackson.databind.ObjectMapper json(){return new cn.jualn.miniapp.config.JacksonConfig().objectMapper(new org.springframework.http.converter.json.Jackson2ObjectMapperBuilder());}
        @Bean MapperFactoryBean<PostOrganizationMapper> organizationRows(SqlSessionFactory s){return mapper(PostOrganizationMapper.class,s);}
        @Bean PostOrganizationService organization(PostOrganizationMapper mapper,PostRepresentationTags tags,PostCursorCodec cursors){return new PostOrganizationService(mock(PostDictionaryAuthorization.class),mapper,tags,cursors,mock(cn.jualn.miniapp.module.admin.operation.service.AdminOperationLogService.class));}
        @Bean TestMediaService media(){var m=mock(TestMediaService.class);when(m.prepareNewAttachments(any(),any())).thenAnswer(i->i.getArgument(1));when(m.listAttachments(any(),any())).thenReturn(List.of());when(m.listSimpleAttachments(any(),any())).thenReturn(List.of());return m;}
        @Bean cn.jualn.miniapp.module.post.converter.PostConverter postConverter(){return org.mapstruct.factory.Mappers.getMapper(cn.jualn.miniapp.module.post.converter.PostConverter.class);}
        @Bean UserService users(){var u=mock(UserService.class);when(u.getSimpleInfo(anyLong())).thenAnswer(i->cn.jualn.miniapp.module.user.bo.UserSimpleBO.builder().id(i.getArgument(0)).nickname("synthetic").build());when(u.batchGetSimple(any())).thenAnswer(i->{var out=new HashMap<Long,cn.jualn.miniapp.module.user.bo.UserSimpleBO>();for(Long id:(java.util.Collection<Long>)i.getArgument(0))out.put(id,cn.jualn.miniapp.module.user.bo.UserSimpleBO.builder().id(id).nickname("synthetic").build());return out;});return u;}
        @Bean UserAccountService accounts(){var s=mock(UserAccountService.class);when(s.getUserAuthInfo(anyLong())).thenAnswer(i->{var a=new cn.jualn.miniapp.module.user.bo.UserAuthBO();a.setStatus(cn.jualn.miniapp.module.user.enums.UserStatus.NORMAL);return a;});return s;}
        @Bean PostSafetyChecks safety(){return mock(PostSafetyChecks.class);}
        @Bean MentorQualification mentor(){var m=mock(MentorQualification.class);when(m.currentForOriginal(anyLong(),anyString())).thenAnswer(i->m.evaluate(i.getArgument(0)));return m;}
        @Bean cn.jualn.miniapp.module.authorization.service.CurrentAuthorization authorization(){return mock(cn.jualn.miniapp.module.authorization.service.CurrentAuthorization.class);}
        @Bean org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory redisConnection(){return new org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory("127.0.0.1",16381);}
        @Bean cn.jualn.miniapp.infrastructure.cache.RedisService redis(org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory c,com.fasterxml.jackson.databind.ObjectMapper json){return new cn.jualn.miniapp.infrastructure.cache.RedisService(new cn.jualn.miniapp.config.RedisConfig().redisTemplate(c),json);}
        @Bean AuditReservationService reservation(){var r=mock(AuditReservationService.class);when(r.reserveAuditLogs(any())).thenReturn(cn.jualn.miniapp.module.audit.bo.AuditReserveResultBO.builder().textAuditLogId(1L).build());return r;}
        @Bean ContentSafetyInvalidation invalidation(){return mock(ContentSafetyInvalidation.class);}
        @Bean CanonicalCommentService canonical(){return mock(CanonicalCommentService.class);}
        @Bean cn.jualn.miniapp.module.article.service.ArticleService articles(){return mock(cn.jualn.miniapp.module.article.service.ArticleService.class);}
        @Bean cn.jualn.miniapp.module.activity.service.ActivityPublicAccess activityAccess(){return mock(cn.jualn.miniapp.module.activity.service.ActivityPublicAccess.class);}
        @Bean cn.jualn.miniapp.module.publicevent.service.PublicEventPublicAccess eventAccess(){return mock(cn.jualn.miniapp.module.publicevent.service.PublicEventPublicAccess.class);}
        @Bean cn.jualn.miniapp.module.activity.service.ActivityInteractionSubject activities(){return mock(cn.jualn.miniapp.module.activity.service.ActivityInteractionSubject.class);}
        @Bean cn.jualn.miniapp.module.publicevent.service.PublicEventInteractionSubject events(){return mock(cn.jualn.miniapp.module.publicevent.service.PublicEventInteractionSubject.class);}
        @Bean InteractionSubjects subjects(PostInteractionSubject s){return new InteractionSubjects(List.of(s));}
        @Bean cn.jualn.miniapp.module.notify.service.NotifyService notifications(){return mock(cn.jualn.miniapp.module.notify.service.NotifyService.class);}
        @Bean cn.jualn.miniapp.module.admin.operation.service.AdminAuditJournal audit(){return mock(cn.jualn.miniapp.module.admin.operation.service.AdminAuditJournal.class);}
        @Bean MapperFactoryBean<cn.jualn.miniapp.module.report.mapper.ReportMapper> reports(SqlSessionFactory s){return mapper(cn.jualn.miniapp.module.report.mapper.ReportMapper.class,s);}
        @Bean MapperFactoryBean<cn.jualn.miniapp.module.report.mapper.ReportResolutionMapper> resolutions(SqlSessionFactory s){return mapper(cn.jualn.miniapp.module.report.mapper.ReportResolutionMapper.class,s);}
        @Bean cn.jualn.miniapp.common.mapper.EnumConverter enums(){return new cn.jualn.miniapp.common.mapper.EnumConverter();}
        @Bean cn.jualn.miniapp.module.report.converter.ReportConverter reportConverter(){return org.mapstruct.factory.Mappers.getMapper(cn.jualn.miniapp.module.report.converter.ReportConverter.class);}
        @Bean cn.jualn.miniapp.module.subject.service.SubjectExistenceService existence(){return mock(cn.jualn.miniapp.module.subject.service.SubjectExistenceService.class);}
        @Bean cn.jualn.miniapp.module.content.service.AdminContentService managedContent(PostGovernanceService governance){
            var service=mock(cn.jualn.miniapp.module.content.service.AdminContentService.class);
            when(service.getContentDetailForReport(any(),anyLong())).thenAnswer(i->{long id=i.getArgument(1);var p=db.queryForMap("SELECT admin_hidden FROM post WHERE id=?",id);var d=new cn.jualn.miniapp.module.content.bo.AdminContentDetailBO();boolean hidden=p.get("admin_hidden") instanceof Boolean flag?flag:((Number)p.get("admin_hidden")).intValue()==1;d.setStatus(hidden?"removed":"published");d.setAvailableActions(hidden?List.of():List.of("take-down"));return d;});
            doAnswer(i->{governance.lockForReport(i.getArgument(1));return null;}).when(service).lockReportTarget(any(),anyLong());
            doAnswer(i->{cn.jualn.miniapp.module.content.bo.AdminContentActionBO c=i.getArgument(0);governance.takeDownPost(AdminPostActionBO.builder().postId(c.getContentId()).operatorId(c.getOperatorId()).reason(c.getReason()).build());return null;}).when(service).executeAction(any());return service;
        }
        @Bean MapperFactoryBean<LikeRecordMapper> likeRecords(SqlSessionFactory s){return mapper(LikeRecordMapper.class,s);}
        @Bean MapperFactoryBean<ShareRecordMapper> shareRecords(SqlSessionFactory s){return mapper(ShareRecordMapper.class,s);}
        @Bean ArticleCommentNotifications articleCommentNotifications(){return mock(ArticleCommentNotifications.class);}
        @Bean MapperFactoryBean<cn.jualn.miniapp.module.user.mapper.UserProfileMapper> accountRows(SqlSessionFactory s){return mapper(cn.jualn.miniapp.module.user.mapper.UserProfileMapper.class,s);}
        @Bean MapperFactoryBean<cn.jualn.miniapp.module.search.mapper.SearchDocMapper> searchRows(SqlSessionFactory s){return mapper(cn.jualn.miniapp.module.search.mapper.SearchDocMapper.class,s);}
        @Bean MapperFactoryBean<cn.jualn.miniapp.module.post.mapper.MentorQuestionMapper> questionRows(SqlSessionFactory s){return mapper(cn.jualn.miniapp.module.post.mapper.MentorQuestionMapper.class,s);}
        @Bean MapperFactoryBean<cn.jualn.miniapp.module.media.mapper.MediaUploadRecordMapper> fileRows(SqlSessionFactory s){return mapper(cn.jualn.miniapp.module.media.mapper.MediaUploadRecordMapper.class,s);}
        @Bean MapperFactoryBean<cn.jualn.miniapp.module.media.mapper.MediaAttachmentMapper> attachmentRows(SqlSessionFactory s){return mapper(cn.jualn.miniapp.module.media.mapper.MediaAttachmentMapper.class,s);}
        @Bean ViewLogMapper viewLogs(){return mock(ViewLogMapper.class);}
    }
}
