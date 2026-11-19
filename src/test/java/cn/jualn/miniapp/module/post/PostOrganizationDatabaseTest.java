package cn.jualn.miniapp.module.post;

import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.module.media.enums.MediaType;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.common.exception.GlobalExceptionHandler;
import cn.jualn.miniapp.module.post.bo.PostCreateBO;
import cn.jualn.miniapp.module.post.bo.PostDictionarySaveBO;
import cn.jualn.miniapp.module.post.bo.PostEditBO;
import cn.jualn.miniapp.module.post.bo.PostFeedPageBO;
import cn.jualn.miniapp.module.post.bo.PostTopicBO;
import cn.jualn.miniapp.module.post.bo.PostFeedQueryBO;
import cn.jualn.miniapp.module.post.service.MentorQualification;
import cn.jualn.miniapp.module.post.service.PostCommentPermissions;
import cn.jualn.miniapp.module.post.service.PostCursorCodec;
import cn.jualn.miniapp.module.post.service.PostDictionaryAuthorization;
import cn.jualn.miniapp.module.post.service.PostEditingService;
import cn.jualn.miniapp.module.post.service.PostOrganizationService;
import cn.jualn.miniapp.module.post.service.PostRepresentationTags;
import cn.jualn.miniapp.module.post.service.PostSafetyChecks;
import cn.jualn.miniapp.module.post.service.PostService;
import cn.jualn.miniapp.module.post.service.impl.PostAuditCallback;
import cn.jualn.miniapp.module.post.service.impl.PostServiceImpl;
import cn.jualn.miniapp.module.post.mapper.PostMapper;
import cn.jualn.miniapp.module.post.mapper.PostOrganizationMapper;
import cn.jualn.miniapp.module.post.converter.PostConverter;
import cn.jualn.miniapp.module.post.controller.PostController;
import cn.jualn.miniapp.module.post.controller.PostDictionaryController;
import cn.jualn.miniapp.module.post.controller.PostNoStoreFilter;
import cn.jualn.miniapp.module.comment.bo.CommentCreateBO;
import cn.jualn.miniapp.module.comment.bo.CommentPageBO;
import cn.jualn.miniapp.module.comment.service.CanonicalCommentService;
import cn.jualn.miniapp.module.comment.service.CommentService;
import cn.jualn.miniapp.module.comment.service.PostQaCommentPolicy;
import cn.jualn.miniapp.module.comment.service.impl.CommentServiceImpl;
import cn.jualn.miniapp.module.comment.converter.CommentConverter;
import cn.jualn.miniapp.module.comment.mapper.CommentMapper;
import cn.jualn.miniapp.module.comment.controller.CommentController;
import cn.jualn.miniapp.module.media.TestMediaService;
import cn.jualn.miniapp.module.user.service.UserService;
import cn.jualn.miniapp.module.user.bo.UserSimpleBO;
import cn.jualn.miniapp.module.interact.service.InteractionLikeQueries;
import cn.jualn.miniapp.module.audit.bo.AuditReserveResultBO;
import cn.jualn.miniapp.module.audit.service.AuditReservationService;
import cn.jualn.miniapp.module.audit.mapper.ContentAuditLogMapper;
import cn.jualn.miniapp.module.admin.operation.service.AdminOperationLogService;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.module.subject.service.SubjectExistenceService;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mapstruct.factory.Mappers;
import org.flywaydb.core.Flyway;
import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** MySQL migration, SQL, transaction proxies and MVC are real. Identity/safety/media providers are doubles. */
@EnabledIfSystemProperty(named="event.test.jdbcUrl",matches="jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/post_organization_test")
class PostOrganizationDatabaseTest {
    static String url;
    static final List<Map<String,Object>> responses=new ArrayList<>();
    AnnotationConfigApplicationContext context;
    JdbcTemplate jdbc; cn.jualn.miniapp.module.post.service.PostQueries postQueries; cn.jualn.miniapp.module.comment.service.CommentQueries commentQueries; PostService posts; PostEditingService edits; PostOrganizationService dictionaries;
    CommentService comments; MentorQualification qualification; PostSafetyChecks safety; MockMvc mvc;

    @BeforeAll static void migrateUpgradeAndReplay() {
        url=System.getProperty("event.test.jdbcUrl");
        if(!url.matches("jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/post_organization_test")) { throw new IllegalArgumentException("Disposable schema only"); }
        String options="?sslMode=DISABLED&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
        var admin=new JdbcTemplate(new DriverManagerDataSource(url.replace("/post_organization_test","/mysql")+options,"root",""));
        admin.execute("CREATE DATABASE post_organization_test");
        admin.execute("CREATE DATABASE post_organization_replay");
        var baseline=Flyway.configure().dataSource(url+options,"root","").target("39").load(); baseline.migrate();baseline.validate();
        var db=new JdbcTemplate(new DriverManagerDataSource(url+options,"root",""));
        db.update("INSERT INTO post(id,user_id,title,content,status,audit_status,like_count,comment_count) VALUES(1,42,'legacy','legacy body',2,1,7,2)");
        db.update("INSERT INTO comment(id,target_type,target_id,user_id,content,status,audit_status) VALUES(1,1,1,43,'legacy comment',1,1)");
        db.update("INSERT INTO like_record(user_id,target_type,target_id) VALUES(43,1,1)");
        var before=db.queryForMap("SELECT user_id,title,content,status,audit_status,like_count,comment_count,created_at,updated_at FROM post WHERE id=1");
        var upgrade=Flyway.configure().dataSource(url+options,"root","").load();upgrade.migrate();upgrade.validate();
        assertEquals(before,db.queryForMap("SELECT user_id,title,content,status,audit_status,like_count,comment_count,created_at,updated_at FROM post WHERE id=1"));
        assertEquals("NORMAL",db.queryForObject("SELECT post_type FROM post WHERE id=1",String.class));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM comment WHERE id=1",Integer.class));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM like_record WHERE target_id=1",Integer.class));
        var replay=Flyway.configure().dataSource(url.replace("post_organization_test","post_organization_replay")+options,"root","").load();replay.migrate();replay.validate();
    }
    @BeforeEach void setup() {
        context=new AnnotationConfigApplicationContext(Config.class);
        jdbc=new JdbcTemplate(context.getBean(DataSource.class)); posts=context.getBean(PostService.class);postQueries=context.getBean(cn.jualn.miniapp.module.post.service.PostQueries.class);commentQueries=context.getBean(cn.jualn.miniapp.module.comment.service.CommentQueries.class); edits=context.getBean(PostEditingService.class);
        dictionaries=context.getBean(PostOrganizationService.class);comments=context.getBean(CommentService.class);
        qualification=context.getBean(MentorQualification.class);safety=context.getBean(PostSafetyChecks.class);
        when(qualification.evaluate(anyLong())).thenReturn(new MentorQualification.Decision(MentorQualification.State.UNAVAILABLE,null));
        when(qualification.forWrite(anyLong())).thenAnswer(i->qualification.evaluate(i.getArgument(0)));
        UserContext.setUserId(42L);
        mvc=MockMvcBuilders.standaloneSetup(new PostController(posts, context.getBean(cn.jualn.miniapp.module.post.service.PostCommunityService.class), postQueries, context.getBean(cn.jualn.miniapp.module.media.TestMediaService.class), context.getBean(PostConverter.class), edits,org.mockito.Mockito.mock(cn.jualn.miniapp.module.post.service.GrowthRecordAccess.class),org.mockito.Mockito.mock(cn.jualn.miniapp.module.post.service.GrowthRecordService.class)),new PostDictionaryController(dictionaries),new cn.jualn.miniapp.module.post.controller.AdminPostDictionaryController(dictionaries,context.getBean(PostDictionaryAuthorization.class)),new CommentController(comments, context.getBean(cn.jualn.miniapp.module.comment.service.CommunityCommentService.class), context.getBean(cn.jualn.miniapp.module.post.service.PostPublicAccess.class), commentQueries, context.getBean(cn.jualn.miniapp.module.media.TestMediaService.class), context.getBean(CommentConverter.class)))
                .setControllerAdvice(new GlobalExceptionHandler()).addFilters(new PostNoStoreFilter())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(context.getBean(ObjectMapper.class))).build();
    }
    @AfterEach void close() {UserContext.clear();if(context!=null){ context.close(); }}
    @AfterAll static void saveResponses() throws Exception {
        java.nio.file.Files.writeString(java.nio.file.Path.of("target/post-contract-responses.json"),new ObjectMapper().writeValueAsString(responses));
    }
    void capture(String schema,org.springframework.test.web.servlet.MvcResult result) throws Exception {
        responses.add(Map.of("schema",schema,"status",result.getResponse().getStatus(),"body",context.getBean(ObjectMapper.class).readTree(result.getResponse().getContentAsString())));
    }
    long category() {return jdbc.queryForObject("SELECT id FROM post_category WHERE code='ACADEMIC'",Long.class);}
    long topic(String code) {return ((PostTopicBO)dictionaries.create(false,new PostDictionarySaveBO(code,code,null,null,null,Set.of("code","name")),9).value()).id();}
    long post(boolean qa,List<Long> topics) {
        var value=posts.createPost(PostCreateBO.builder().content("test body").postType(qa?PostType.MENTOR_QA:PostType.NORMAL).categoryId(qa?category():null).topicIds(topics).build());
        jdbc.update("UPDATE post SET audit_status=1 WHERE id=?",value.getId());return value.getId();
    }
    PostEditBO change(String text) {return new PostEditBO(null,text,null,null,null,Set.of("content"));}
    CommentCreateBO comment(long post,Long root,Long reply) {
        var c=new CommentCreateBO();c.setTargetType(TargetType.POST);c.setTargetId(post);c.setParentId(root);c.setReplyToCommentId(reply);c.setContent("answer");return c;
    }
    long visibleAnswer(long post) {
        jdbc.update("INSERT INTO comment(target_type,target_id,user_id,content,status,audit_status,mentor_evidence) VALUES(1,?,77,'mentor answer',1,1,'trusted:test:77')",post);
        return jdbc.queryForObject("SELECT MAX(id) FROM comment WHERE target_id=?",Long.class,post);
    }
    @Test void defaultNormalAndExplicitAllHaveStableDeduplicatedPagination() {
        long t1=topic("FEED_A"),t2=topic("FEED_B");long normal=post(false,List.of(t1,t2));long qa=post(true,List.of(t1,t2));
        var query=new PostFeedQueryBO();query.setTopicId(t1);query.setPageSize(1);
        var first=(PostFeedPageBO)postQueries.pagePost(query);assertEquals(normal,first.getList().get(0).getId());assertNotNull(first.getCursor());
        query.setCursor(first.getCursor());var next=postQueries.pagePost(query);assertTrue(next.getList().stream().noneMatch(p->p.getId()==normal));
        query.setPostType("ALL");assertEquals(400,assertThrows(ContractProblemException.class,()->postQueries.pagePost(query)).getStatus().value());
        query.setCursor(null);assertEquals(qa,postQueries.pagePost(query).getList().get(0).getId());
        query.setLastId(qa);assertThrows(ContractProblemException.class,()->postQueries.pagePost(query));
    }
    @Test void invalidCategoryAndTopicsLeaveNoPartialPost() {
        int before=jdbc.queryForObject("SELECT COUNT(*) FROM post",Integer.class);
        assertThrows(ContractProblemException.class,()->posts.createPost(PostCreateBO.builder().content("qa").postType(PostType.MENTOR_QA).build()));
        assertThrows(ContractProblemException.class,()->posts.createPost(PostCreateBO.builder().content("qa").postType(PostType.MENTOR_QA).categoryId(999999L).build()));
        long topic=topic("LIMIT_A");
        assertThrows(ContractProblemException.class,()->post(false,List.of(topic,topic)));
        assertThrows(ContractProblemException.class,()->post(false,List.of(topic,topic+1,topic+2,topic+3)));
        assertEquals(before,jdbc.queryForObject("SELECT COUNT(*) FROM post",Integer.class));
    }
    @Test void disabledHistoryRemainsReadableButExplicitResubmissionFails() {
        long t=topic("HISTORY_A"),id=post(true,List.of(t));
        var old=dictionaries.get(false,t);dictionaries.edit(false,t,new PostDictionarySaveBO(null,null,"INACTIVE",null,null,Set.of("status")),old.etag(),9);
        assertEquals("INACTIVE",postQueries.getPostDetail(id).getTopics().get(0).status());
        var before=edits.get(id);var changed=edits.edit(id,change("new text"),before.etag());assertEquals(List.of(t),changed.value().topicIds());
        assertThrows(ContractProblemException.class,()->edits.edit(id,new PostEditBO(null,null,null,List.of(t),null,Set.of("topicIds")),changed.etag()));
        var cat=dictionaries.get(true,category());
        assertThrows(ContractProblemException.class,()->dictionaries.edit(true,category(),new PostDictionarySaveBO(null,null,null,null,List.of("NORMAL"),Set.of("postTypes")),cat.etag(),9));
    }
    @Test void safetyRejectionUnavailableAndConcurrentChangePreserveEffectiveContent() {
        long id=post(false,List.of());var before=edits.get(id);
        doThrow(PostProblems.problem(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,"post-content-rejected","rejected")).when(safety).check(anyLong(),anyLong(),any(),any());
        assertEquals(422,assertThrows(ContractProblemException.class,()->edits.edit(id,change("rejected"),before.etag())).getStatus().value());
        assertEquals(before,edits.get(id));reset(safety);
        doThrow(PostProblems.problem(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"post-safety-check-unavailable","unavailable")).when(safety).check(anyLong(),anyLong(),any(),any());
        assertEquals(503,assertThrows(ContractProblemException.class,()->edits.edit(id,change("unavailable"),before.etag())).getStatus().value());reset(safety);
        doAnswer(i->{jdbc.update("UPDATE post SET content='concurrent',revision=revision+1 WHERE id=?",id);return null;}).when(safety).check(anyLong(),anyLong(),any(),any());
        assertEquals(412,assertThrows(ContractProblemException.class,()->edits.edit(id,change("losing edit"),before.etag())).getStatus().value());
        assertEquals("concurrent",edits.get(id).value().content());
    }
    @Test void httpEditEnforcesAuthorStrongTagsAndPatchSemantics() throws Exception {
        long id=post(false,List.of());var before=edits.get(id);
        mvc.perform(get("/v1/post/{id}",id).param("view","edit")).andExpect(status().isOk()).andExpect(header().string("ETag",before.etag())).andExpect(jsonPath("content").value("test body"));
        mvc.perform(patch("/v1/post/{id}",id).contentType("application/json").content("{\"content\":\"new\"}")).andExpect(status().isPreconditionRequired());
        for(String tag:List.of("*","W/"+before.etag(),before.etag()+","+before.etag(),""))
            { mvc.perform(patch("/v1/post/{id}",id).header("If-Match",tag).contentType("application/json").content("{\"content\":\"new\"}")).andExpect(status().isBadRequest()).andExpect(jsonPath("errors[0].code").value("INVALID_ETAG")); }
        mvc.perform(patch("/v1/post/{id}",id).header("If-Match",before.etag()).contentType("application/json").content("{\"title\":null,\"content\":\"new\"}")).andExpect(status().isOk()).andExpect(jsonPath("content").value("new"));
        mvc.perform(patch("/v1/post/{id}",id).header("If-Match",before.etag()).contentType("application/json").content("{\"content\":\"stale\"}")).andExpect(status().isPreconditionFailed());
        mvc.perform(patch("/v1/post/{id}",id).header("If-Match",edits.get(id).etag()).contentType("application/json").content("{\"postType\":\"MENTOR_QA\"}")).andExpect(status().isConflict());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/v1/post/{id}",id).contentType("application/json").content("{}" )).andExpect(status().isMethodNotAllowed());
        UserContext.setUserId(99L);mvc.perform(get("/v1/post/{id}",id).param("view","edit")).andExpect(status().isForbidden());
    }
    @Test void qualificationUnavailableAndReliableIneligibleAreDifferentAndAdminIsNotMentor() {
        long id=post(true,List.of());
        assertEquals(503,assertThrows(ContractProblemException.class,()->comments.createComment(comment(id,null,null))).getStatus().value());
        when(qualification.evaluate(anyLong())).thenReturn(new MentorQualification.Decision(MentorQualification.State.INELIGIBLE,null));
        assertEquals(403,assertThrows(ContractProblemException.class,()->comments.createComment(comment(id,null,null))).getStatus().value());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM comment WHERE target_id=?",Integer.class,id));
    }
    @Test void trustedAnswerAndAuthorFollowUpDoNotDependOnAuthorQualification() {
        long id=post(true,List.of());long root=visibleAnswer(id);
        var c=comment(id,root,root);long followup=comments.createComment(c);
        assertEquals(77L,jdbc.queryForObject("SELECT reply_to_uid FROM comment WHERE id=?",Long.class,followup));
        assertEquals(root,jdbc.queryForObject("SELECT reply_to_comment_id FROM comment WHERE id=?",Long.class,followup));
        assertNull(jdbc.queryForObject("SELECT mentor_evidence FROM comment WHERE id=?",String.class,followup));
        jdbc.update("UPDATE comment SET status=1 WHERE id=?",followup);
        UserContext.setUserId(88L);when(qualification.evaluate(88)).thenReturn(new MentorQualification.Decision(MentorQualification.State.VALID,"trusted:test:88"));
        long reply=comments.createComment(comment(id,root,followup));assertEquals("trusted:test:88",jdbc.queryForObject("SELECT mentor_evidence FROM comment WHERE id=?",String.class,reply));
        UserContext.setUserId(42L);assertEquals(400,assertThrows(ContractProblemException.class,()->comments.createComment(comment(id,root,followup))).getStatus().value());
    }
    @Test void crossPostCrossRootSpoofedUidAndSecondLevelParentAreRejected() {
        long id=post(true,List.of()),other=post(true,List.of());long root=visibleAnswer(id),foreign=visibleAnswer(other),otherRoot=visibleAnswer(id);
        assertEquals(400,assertThrows(ContractProblemException.class,()->comments.createComment(comment(id,root,foreign))).getStatus().value());
        assertEquals(400,assertThrows(ContractProblemException.class,()->comments.createComment(comment(id,root,otherRoot))).getStatus().value());
        var spoof=comment(id,root,root);spoof.setReplyToUid(99L);assertThrows(ContractProblemException.class,()->comments.createComment(spoof));
        long reply=comments.createComment(comment(id,root,root));jdbc.update("UPDATE comment SET status=1 WHERE id=?",reply);
        assertThrows(BusinessException.class,()->comments.createComment(comment(id,reply,root)));
        assertEquals(400,assertThrows(ContractProblemException.class,()->comments.createComment(comment(id,root,null))).getStatus().value());
    }
    @Test void normalCommentKeepsLegacyUidAndInvisiblePostCannotBeCommentedOrRead() {
        long id=post(false,List.of()),root=visibleAnswer(id);var c=comment(id,root,null);c.setReplyToUid(93L);
        long reply=comments.createComment(c);assertEquals(93L,jdbc.queryForObject("SELECT reply_to_uid FROM comment WHERE id=?",Long.class,reply));
        jdbc.update("UPDATE post SET audit_status=2 WHERE id=?",id);
        assertThrows(BusinessException.class,()->comments.createComment(comment(id,null,null)));
        var query=new CommentPageBO();query.setTargetType(TargetType.POST);query.setTargetId(id);assertThrows(BusinessException.class,()->commentQueries.pageComment(query));
    }
    @Test void staleCreationAuditCallbackCannotOverwriteCheckedEdit() {
        long id=post(false,List.of());edits.edit(id,change("checked new text"),edits.get(id).etag());
        context.getBean(PostAuditCallback.class).onReject(id,1L,"late rejection");
        assertEquals("checked new text",postQueries.getPostDetail(id).getContent());
    }
    @Test void twoWritersWithSameTagOnlyOneCommits() throws Exception {
        long id=post(false,List.of());String tag=edits.get(id).etag();var checked=new CountDownLatch(1);var release=new CountDownLatch(1);
        doAnswer(i->{if(((String)i.getArgument(2)).contains("slow")) {checked.countDown();assertTrue(release.await(10,TimeUnit.SECONDS));}return null;}).when(safety).check(anyLong(),anyLong(),any(),any());
        var executor=Executors.newSingleThreadExecutor();
        try {
            var losing=executor.submit(()->{UserContext.setUserId(42L);try{return assertThrows(ContractProblemException.class,()->edits.edit(id,change("slow edit"),tag)).getStatus().value();}finally{UserContext.clear();}});
            assertTrue(checked.await(10,TimeUnit.SECONDS));edits.edit(id,change("winner"),tag);release.countDown();
            assertEquals(412,losing.get(10,TimeUnit.SECONDS));assertEquals("winner",edits.get(id).value().content());
        } finally {release.countDown();executor.shutdownNow();}
    }
    @Test void failedMediaBindingRollsBackPostAndAllTopicLinks() {
        long t=topic("ROLLBACK_A");int before=jdbc.queryForObject("SELECT COUNT(*) FROM post",Integer.class);
        doThrow(new IllegalStateException("synthetic binding failure")).when(context.getBean(TestMediaService.class)).replaceAttachments(any());
        var c=PostCreateBO.builder().content("transaction").topicIds(List.of(t)).attachmentItems(List.of(cn.jualn.miniapp.module.media.bo.AttachmentItemBO.builder().type(MediaType.IMAGE).url("https://cdn.example/img").objectKey("post/42/img").build())).build();
        assertThrows(IllegalStateException.class,()->posts.createPost(c));
        assertEquals(before,jdbc.queryForObject("SELECT COUNT(*) FROM post",Integer.class));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM post_topic WHERE topic_id=?",Integer.class,t));
    }
    @Test void disableSerializesWithNewAssociations() throws Exception {
        long t=topic("RACE_A");var locked=new CountDownLatch(1);var release=new CountDownLatch(1);var executor=Executors.newFixedThreadPool(2);
        try {
            var disable=executor.submit(()->new org.springframework.transaction.support.TransactionTemplate(context.getBean(org.springframework.transaction.PlatformTransactionManager.class)).execute(status->{
                context.getBean(PostOrganizationMapper.class).lockTopic(t);locked.countDown();
                try {assertTrue(release.await(10,TimeUnit.SECONDS));}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
                jdbc.update("UPDATE topic SET status='INACTIVE',revision=revision+1 WHERE id=?",t);return true;
            }));
            assertTrue(locked.await(10,TimeUnit.SECONDS));
            var creation=executor.submit(()->{UserContext.setUserId(42L);try{return assertThrows(ContractProblemException.class,()->post(false,List.of(t))).getType();}finally{UserContext.clear();}});
            release.countDown();assertTrue(disable.get(10,TimeUnit.SECONDS));assertEquals("/problems/post-topic-invalid",creation.get(10,TimeUnit.SECONDS));
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM post_topic WHERE topic_id=?",Integer.class,t));
        } finally {release.countDown();executor.shutdownNow();}
    }
    @Test void dictionaryTagsConflictsAuditAndQueryBindingAreEnforced() {
        long t=topic("DICTIONARY_A");var before=dictionaries.get(false,t);
        var rename=new PostDictionarySaveBO(null,"renamed",null,null,null,Set.of("name"));
        assertEquals(428,assertThrows(ContractProblemException.class,()->dictionaries.edit(false,t,rename,null,9)).getStatus().value());
        var after=dictionaries.edit(false,t,rename,before.etag(),9);assertNotEquals(before.etag(),after.etag());
        assertEquals(412,assertThrows(ContractProblemException.class,()->dictionaries.edit(false,t,rename,before.etag(),9)).getStatus().value());
        assertEquals(409,assertThrows(ContractProblemException.class,()->topic("DICTIONARY_A")).getStatus().value());
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM admin_operation_log WHERE target_type='topic' AND target_id=?",Integer.class,t));
        var page=dictionaries.categories("ACTIVE","MENTOR_QA",null,1);assertNotNull(page.nextCursor());
        assertThrows(ContractProblemException.class,()->dictionaries.categories("ALL","MENTOR_QA",page.nextCursor(),1));
        assertEquals(1,dictionaries.categories("ACTIVE","MENTOR_QA",page.nextCursor(),1).items().size());
    }
    @Test void notificationUsesTrustedConcreteReplyRecipientAfterAuditOnly() {
        long id=post(true,List.of());var notifier=context.getBean(cn.jualn.miniapp.module.notify.service.NotifyService.class);var callback=context.getBean(cn.jualn.miniapp.module.comment.service.impl.CommentAuditCallback.class);
        UserContext.setUserId(77L);when(qualification.evaluate(77L)).thenReturn(new MentorQualification.Decision(MentorQualification.State.VALID,"trusted:test:77"));
        long root=comments.createComment(comment(id,null,null));verifyNoInteractions(notifier);
        callback.activateCommentAfterAuditPass(root);callback.activateCommentAfterAuditPass(root);
        assertEquals(1,jdbc.queryForObject("SELECT comment_count FROM post WHERE id=?",Integer.class,id));
        UserContext.setUserId(42L);long followup=comments.createComment(comment(id,root,root));callback.activateCommentAfterAuditPass(followup);
        UserContext.setUserId(88L);when(qualification.evaluate(88L)).thenReturn(new MentorQualification.Decision(MentorQualification.State.VALID,"trusted:test:88"));
        long reply=comments.createComment(comment(id,root,followup));callback.activateCommentAfterAuditPass(reply);
        var payloads=org.mockito.ArgumentCaptor.forClass(cn.jualn.miniapp.module.notify.payload.NotifyPayload.class);verify(notifier,times(3)).processNotificationPayload(payloads.capture());
        assertEquals(List.of(42L,77L,42L),payloads.getAllValues().stream().map(p->p.getReceiverId()).toList());
        assertEquals(3,jdbc.queryForObject("SELECT comment_count FROM post WHERE id=?",Integer.class,id));
        assertEquals(2,jdbc.queryForObject("SELECT reply_count FROM comment WHERE id=?",Integer.class,root));
    }
    @Test void actualResponsesConformToCanonicalSchemasAndHaveDynamicReplyPermissions() throws Exception {
        long id=post(true,List.of()),root=visibleAnswer(id);long reply=comments.createComment(comment(id,root,root));jdbc.update("UPDATE comment SET status=1 WHERE id=?",reply);
        capture("PostResult",mvc.perform(get("/v1/post/{id}",id)).andExpect(status().isOk()).andExpect(jsonPath("data.commentPermission.state").value("BLOCKED")).andReturn());
        capture("PostFeedResult",mvc.perform(get("/v1/post").param("postType","ALL").param("pageSize","1")).andExpect(status().isOk()).andReturn());
        capture("PostEditableRepresentation",mvc.perform(get("/v1/post/{id}",id).param("view","edit")).andExpect(status().isOk()).andReturn());
        capture("PostCategoryPage",mvc.perform(get("/v1/post-categories").param("postType","MENTOR_QA").param("pageSize","1")).andExpect(status().isOk()).andReturn());
        capture("PostTopicPage",mvc.perform(get("/v1/topics")).andExpect(status().isOk()).andReturn());
        capture("PostCommentPageResult",mvc.perform(get("/v1/comment").param("targetType","POST").param("targetId",Long.toString(id))).andExpect(status().isOk()).andExpect(jsonPath("data.list[0].replyPermission.state").value("ALLOWED")).andReturn());
        capture("PostReplyPageResult",mvc.perform(get("/v1/comment/replies").param("targetType","POST").param("targetId",Long.toString(id)).param("parentId",Long.toString(root))).andExpect(status().isOk()).andExpect(jsonPath("data.list[0].replyToCommentId").value(Long.toString(root))).andReturn());
    }
    @Test void normalLegacyRepliesAndOptionalAttachmentFieldsConform() throws Exception {
        long id=post(false,List.of()),root=visibleAnswer(id);var c=comment(id,root,null);c.setReplyToUid(77L);long reply=comments.createComment(c);jdbc.update("UPDATE comment SET status=1 WHERE id=?",reply);
        capture("PostReplyPageResult",mvc.perform(get("/v1/comment/replies").param("targetType","POST").param("targetId",Long.toString(id)).param("parentId",Long.toString(root))).andExpect(status().isOk()).andExpect(jsonPath("data.list[0].replyToCommentId").doesNotExist()).andReturn());
        var attachment=cn.jualn.miniapp.module.media.bo.MediaAttachmentBO.builder().type(MediaType.IMAGE).url("https://cdn.example/legacy").build();
        when(context.getBean(TestMediaService.class).listAttachments(TargetType.POST,id)).thenReturn(List.of(attachment));
        var old=edits.get(id);attachment.setVerified(true);assertNotEquals(old.etag(),edits.get(id).etag());
        capture("PostEditableRepresentation",mvc.perform(get("/v1/post/{id}",id).param("view","edit")).andExpect(status().isOk()).andExpect(jsonPath("attachmentItems[0].objectKey").doesNotExist()).andReturn());
    }
    @Test void likedDefaultNormalCanReturnSparsePageButAdvancesLikeCursor() {
        long id=post(true,List.of());when(context.getBean(InteractionLikeQueries.class).pageUserLikes(any())).thenReturn(List.of(cn.jualn.miniapp.module.interact.bo.UserLikeBO.builder().id(500L).targetId(id).build()));
        var normal=postQueries.pageUserLikedPosts(42L,null,1);assertTrue(normal.getList().isEmpty());assertTrue(normal.getHasMore());assertEquals(500L,normal.getNextCursor());
        assertEquals(id,postQueries.pageUserLikedPosts(42L,null,1,"ALL").getList().get(0).getId());
        assertEquals(id,postQueries.pageUserLikedPosts(42L,null,1,"MENTOR_QA").getList().get(0).getId());
    }
    @Test void adminDictionaryHttpCreatesReadsPatchesAndNeverDeletes() throws Exception {
        var previous=cn.dev33.satoken.SaManager.getStpInterface();var storage=mock(cn.dev33.satoken.context.model.SaStorage.class);Map<String,Object> values=new HashMap<>();
        when(storage.get(org.mockito.ArgumentMatchers.anyString())).thenAnswer(i->values.get(i.getArgument(0)));
        when(storage.set(org.mockito.ArgumentMatchers.anyString(),any())).thenAnswer(i->{values.put(i.getArgument(0),i.getArgument(1));return storage;});
        when(storage.delete(org.mockito.ArgumentMatchers.anyString())).thenAnswer(i->{values.remove(i.getArgument(0));return storage;});
        cn.dev33.satoken.SaManager.getSaTokenContext().setContext(mock(cn.dev33.satoken.context.model.SaRequest.class),mock(cn.dev33.satoken.context.model.SaResponse.class),storage);
        cn.dev33.satoken.SaManager.setStpInterface(new cn.dev33.satoken.stp.StpInterface() {
            public List<String> getPermissionList(Object id,String type) {return List.of("post-dictionary:read","post-dictionary:manage");}
            public List<String> getRoleList(Object id,String type) {return List.of("admin");}
        });
        try {
            cn.jualn.miniapp.common.security.AdminStpUtil.STP_LOGIC.switchTo(9L);
            var created=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/v1/admin/topics").contentType("application/json").content("{\"code\":\"HTTP_ADMIN_A\",\"name\":\"topic\"}")).andExpect(status().isCreated()).andReturn();capture("PostTopic",created);
            String location=created.getResponse().getHeader("Location"),tag=created.getResponse().getHeader("ETag");assertNotNull(location);assertNotNull(tag);
            capture("PostTopic",mvc.perform(get(location)).andExpect(status().isOk()).andExpect(header().string("ETag",tag)).andReturn());
            mvc.perform(patch(location).contentType("application/json").content("{\"name\":\"renamed\"}")).andExpect(status().isPreconditionRequired());
            capture("PostTopic",mvc.perform(patch(location).header("If-Match",tag).contentType("application/json").content("{\"name\":\"renamed\",\"status\":\"INACTIVE\"}")).andExpect(status().isOk()).andReturn());
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(location)).andExpect(status().isMethodNotAllowed());
            capture("PostCategory",mvc.perform(get("/v1/admin/post-categories/{id}",category())).andExpect(status().isOk()).andReturn());
            capture("PostTopicPage",mvc.perform(get("/v1/admin/topics")).andExpect(status().isOk()).andReturn());
            capture("PostCategoryPage",mvc.perform(get("/v1/admin/post-categories")).andExpect(status().isOk()).andReturn());
            var categoryCreated=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/v1/admin/post-categories").contentType("application/json").content("{\"code\":\"HTTP_CATEGORY_A\",\"name\":\"qa category\",\"postTypes\":[\"MENTOR_QA\"]}")).andExpect(status().isCreated()).andReturn();capture("PostCategory",categoryCreated);
            var categoryJson=context.getBean(ObjectMapper.class).readTree(categoryCreated.getResponse().getContentAsString());long categoryId=categoryJson.get("id").asLong();
            long qa=posts.createPost(PostCreateBO.builder().content("question").postType(PostType.MENTOR_QA).categoryId(categoryId).build()).getId();jdbc.update("UPDATE post SET audit_status=1 WHERE id=?",qa);
            var categoryDisabled=mvc.perform(patch(categoryCreated.getResponse().getHeader("Location")).header("If-Match",categoryCreated.getResponse().getHeader("ETag")).contentType("application/json").content("{\"status\":\"INACTIVE\"}")).andExpect(status().isOk()).andReturn();capture("PostCategory",categoryDisabled);
            assertEquals("INACTIVE",postQueries.getPostDetail(qa).getCategory().status());var edited=edits.edit(qa,change("question edited"),edits.get(qa).etag());
            assertEquals(categoryId,edited.value().categoryId());
            assertEquals("/problems/post-category-invalid",assertThrows(ContractProblemException.class,()->edits.edit(qa,new cn.jualn.miniapp.module.post.bo.PostEditBO(null,null,categoryId,null,null,Set.of("categoryId")),edited.etag())).getType());
        } finally {cn.dev33.satoken.SaManager.setStpInterface(previous);cn.dev33.satoken.SaManager.getSaTokenContext().clearContext();}
    }
    @Configuration @EnableTransactionManagement
    @Import({cn.jualn.miniapp.module.post.service.PostCommunityService.class,cn.jualn.miniapp.module.comment.service.CommunityCommentService.class,cn.jualn.miniapp.module.post.service.PostCommunityNotifications.class,cn.jualn.miniapp.module.audit.service.CommunitySafetyStates.class,cn.jualn.miniapp.module.post.service.PostQueries.class,cn.jualn.miniapp.module.post.service.PostGovernanceService.class,
            cn.jualn.miniapp.module.comment.service.CommentQueries.class,cn.jualn.miniapp.module.comment.service.CommentGovernanceService.class,
            cn.jualn.miniapp.module.comment.service.CommentCounters.class,cn.jualn.miniapp.module.post.service.PostInteractionSubject.class,
            cn.jualn.miniapp.module.audit.service.AuditLogService.class,cn.jualn.miniapp.module.admin.operation.AdminAuditTestConfiguration.class,cn.jualn.miniapp.module.post.service.PostPublicAccess.class,PostServiceImpl.class,PostEditingService.class,PostOrganizationService.class,PostRepresentationTags.class,PostCursorCodec.class,PostCommentPermissions.class,PostQaCommentPolicy.class,PostAuditCallback.class,CommentServiceImpl.class,cn.jualn.miniapp.module.comment.service.impl.CommentAuditCallback.class,cn.jualn.miniapp.module.admin.operation.service.impl.AdminOperationLogServiceImpl.class})
    @cn.jualn.miniapp.module.post.OrdinaryGrowthTestConfiguration.Fixture
    static class Config {
        @Bean cn.jualn.miniapp.module.authorization.service.CurrentAuthorization authorization() {return mock(cn.jualn.miniapp.module.authorization.service.CurrentAuthorization.class);}
        @Bean DataSource source() {return new DriverManagerDataSource(url+"?sslMode=DISABLED&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai","root","");}
        @Bean DataSourceTransactionManager transactions(DataSource source) {return new DataSourceTransactionManager(source);}
        @Bean ObjectMapper json() {return new cn.jualn.miniapp.config.JacksonConfig().objectMapper(new org.springframework.http.converter.json.Jackson2ObjectMapperBuilder()).addMixIn(org.springframework.http.ProblemDetail.class,org.springframework.http.converter.json.ProblemDetailJacksonMixin.class);}
        @Bean SqlSessionFactory sessions(DataSource source) throws Exception {
            var factory=new MybatisSqlSessionFactoryBean();factory.setDataSource(source);var config=new MybatisConfiguration();config.setMapUnderscoreToCamelCase(true);factory.setConfiguration(config);
            var global=new com.baomidou.mybatisplus.core.config.GlobalConfig();global.setMetaObjectHandler(new cn.jualn.miniapp.config.MybatisPlusConfig().metaObjectHandler());global.setDbConfig(new com.baomidou.mybatisplus.core.config.GlobalConfig.DbConfig());global.getDbConfig().setLogicNotDeleteValue("NULL");global.getDbConfig().setLogicDeleteValue("NOW()");factory.setGlobalConfig(global);
            factory.setMapperLocations(new ClassPathResource("mapper/MentorAnswerEvidence.xml"), new ClassPathResource("mapper/MentorQuestionMapper.xml"), new ClassPathResource("mapper/PostMapper.xml"),new ClassPathResource("mapper/PostOrganizationMapper.xml"),new ClassPathResource("mapper/CommentMapper.xml"));return factory.getObject();
        }
        <T> MapperFactoryBean<T> mapper(Class<T> type,SqlSessionFactory s) {var bean=new MapperFactoryBean<>(type);bean.setSqlSessionFactory(s);return bean;}
        @Bean MapperFactoryBean<cn.jualn.miniapp.module.post.mapper.PostCommunityMapper> community(org.apache.ibatis.session.SqlSessionFactory s){return mapper(cn.jualn.miniapp.module.post.mapper.PostCommunityMapper.class,s);}
        @Bean MapperFactoryBean<cn.jualn.miniapp.module.comment.mapper.CommunityCommentMapper> communityComments(org.apache.ibatis.session.SqlSessionFactory s){return mapper(cn.jualn.miniapp.module.comment.mapper.CommunityCommentMapper.class,s);}
        @Bean MapperFactoryBean<cn.jualn.miniapp.module.audit.mapper.ContentSafetyReviewMapper> safetyStates(org.apache.ibatis.session.SqlSessionFactory s){return mapper(cn.jualn.miniapp.module.audit.mapper.ContentSafetyReviewMapper.class,s);}
        @Bean cn.jualn.miniapp.module.user.service.UserAccountService accounts(){var a=mock(cn.jualn.miniapp.module.user.service.UserAccountService.class);when(a.getUserAuthInfo(anyLong())).thenReturn(cn.jualn.miniapp.module.user.bo.UserAuthBO.builder().status(cn.jualn.miniapp.module.user.enums.UserStatus.NORMAL).build());return a;}
        @Bean cn.jualn.miniapp.module.audit.service.ContentSafetyInvalidation invalidation(){return mock(cn.jualn.miniapp.module.audit.service.ContentSafetyInvalidation.class);}
        @Bean MapperFactoryBean<PostMapper> posts(SqlSessionFactory s) {return mapper(PostMapper.class,s);}
        @Bean MapperFactoryBean<PostOrganizationMapper> organization(SqlSessionFactory s) {return mapper(PostOrganizationMapper.class,s);}
        @Bean MapperFactoryBean<CommentMapper> comments(SqlSessionFactory s) {return mapper(CommentMapper.class,s);}
        @Bean MapperFactoryBean<ContentAuditLogMapper> logs(SqlSessionFactory s) {return mapper(ContentAuditLogMapper.class,s);}
        @Bean MapperFactoryBean<cn.jualn.miniapp.module.admin.operation.mapper.AdminOperationLogMapper> adminLogs(SqlSessionFactory s) {return mapper(cn.jualn.miniapp.module.admin.operation.mapper.AdminOperationLogMapper.class,s);}
        @Bean PostConverter postConverter() {return Mappers.getMapper(PostConverter.class);}
        @Bean cn.jualn.miniapp.common.mapper.EnumConverter enums() {return new cn.jualn.miniapp.common.mapper.EnumConverter();}
        @Bean CommentConverter commentConverter() {return Mappers.getMapper(CommentConverter.class);}
        @Bean PostDictionaryAuthorization dictionaryAuth() {return mock(PostDictionaryAuthorization.class);}
        @Bean cn.jualn.miniapp.module.activity.mapper.ActivityMapper activityMapper() {return mock(cn.jualn.miniapp.module.activity.mapper.ActivityMapper.class);}
        @Bean cn.jualn.miniapp.module.publicevent.mapper.PublicEventMapper examMapper() {return mock(cn.jualn.miniapp.module.publicevent.mapper.PublicEventMapper.class);}
        @Bean cn.jualn.miniapp.module.notify.service.NotifyService notifier() {return mock(cn.jualn.miniapp.module.notify.service.NotifyService.class);}
        @Bean MentorQualification qualification() {return mock(MentorQualification.class);}
        @Bean PostSafetyChecks safety() {return mock(PostSafetyChecks.class);}
        @Bean cn.jualn.miniapp.module.interact.service.InteractionSubjects subjects(cn.jualn.miniapp.module.post.service.PostInteractionSubject posts) {return new cn.jualn.miniapp.module.interact.service.InteractionSubjects(List.of(posts));}
        @Bean RedisService redis() {return mock(RedisService.class);}
        @Bean SubjectExistenceService target() {return mock(SubjectExistenceService.class);}
        @Bean TestMediaService media() {var m=mock(TestMediaService.class);when(m.listAttachments(any(),any())).thenReturn(List.of());when(m.listSimpleAttachments(any(),any())).thenReturn(List.of());when(m.batchListSimpleAttachments(any(),any())).thenReturn(Map.of());return m;}
        @Bean cn.jualn.miniapp.module.interact.service.InteractionCounts interactionCounts() {
            var counts=mock(cn.jualn.miniapp.module.interact.service.InteractionCounts.class);
            when(counts.batchGetLikeCount(any(),any())).thenReturn(Map.of());
            when(counts.batchGetViewCount(any(),any())).thenReturn(Map.of());
            return counts;
        }
        @Bean InteractionLikeQueries interactions() {var i=mock(InteractionLikeQueries.class);when(i.batchIsLiked(any(),any())).thenReturn(Map.of());return i;}
        @Bean UserService users() {var u=mock(UserService.class);when(u.getSimpleInfo(anyLong())).thenAnswer(i->UserSimpleBO.builder().id(i.getArgument(0)).build());when(u.batchGetSimple(any())).thenAnswer(i->{Map<Long,UserSimpleBO> map=new HashMap<>();for(Long id:(Collection<Long>)i.getArgument(0)){ map.put(id,UserSimpleBO.builder().id(id).build()); }return map;});return u;}
        @Bean AuditReservationService reservations() {var r=mock(AuditReservationService.class);when(r.reserveAuditLogs(any())).thenReturn(AuditReserveResultBO.builder().textAuditLogId(99L).build());return r;}
        @Bean cn.jualn.miniapp.module.activity.service.ActivityInteractionSubject activity() {return mock(cn.jualn.miniapp.module.activity.service.ActivityInteractionSubject.class);}
        @Bean cn.jualn.miniapp.module.publicevent.service.PublicEventInteractionSubject exam() {return mock(cn.jualn.miniapp.module.publicevent.service.PublicEventInteractionSubject.class);}
        @Bean cn.jualn.miniapp.module.comment.service.CommentTargetAccess targetAccess(CommentMapper comments,cn.jualn.miniapp.module.article.service.ArticleService articles,cn.jualn.miniapp.module.post.service.PostPublicAccess posts,cn.jualn.miniapp.module.comment.mapper.CommunityCommentMapper community) {
            return new cn.jualn.miniapp.module.comment.service.CommentTargetAccess(comments, articles, posts, mock(cn.jualn.miniapp.module.activity.service.ActivityPublicAccess.class), mock(cn.jualn.miniapp.module.publicevent.service.PublicEventPublicAccess.class), community);
        }
        @Bean CanonicalCommentService canonical() {return mock(CanonicalCommentService.class);}
        @Bean cn.jualn.miniapp.module.article.service.ArticleService article() {return mock(cn.jualn.miniapp.module.article.service.ArticleService.class);}
    }
}
