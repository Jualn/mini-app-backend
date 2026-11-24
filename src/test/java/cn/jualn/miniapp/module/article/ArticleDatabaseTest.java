package cn.jualn.miniapp.module.article;

import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.exception.*;
import cn.jualn.miniapp.common.web.StrongEtag;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.module.subject.service.SubjectExistenceService;
import cn.jualn.miniapp.module.article.bo.*;
import cn.jualn.miniapp.module.article.service.*;
import cn.jualn.miniapp.module.article.service.impl.ArticleServiceImpl;
import cn.jualn.miniapp.module.article.controller.*;
import cn.jualn.miniapp.module.article.controller.admin.*;
import cn.jualn.miniapp.module.article.converter.ArticleConverter;
import cn.jualn.miniapp.module.article.mapper.*;
import cn.jualn.miniapp.module.comment.bo.*;
import cn.jualn.miniapp.module.comment.mapper.CommentMapper;
import cn.jualn.miniapp.module.comment.service.*;
import cn.jualn.miniapp.module.interact.controller.CanonicalInteractionController;
import cn.jualn.miniapp.module.interact.mapper.*;
import cn.jualn.miniapp.module.interact.service.*;

import cn.jualn.miniapp.module.media.bo.*;
import cn.jualn.miniapp.module.media.converter.MediaConverter;
import cn.jualn.miniapp.module.media.mapper.*;
import cn.jualn.miniapp.module.media.service.*;
import cn.jualn.miniapp.module.media.service.impl.*;
import cn.jualn.miniapp.module.notify.service.NotifyService;
import cn.jualn.miniapp.module.notify.payload.NotifyPayload;
import cn.jualn.miniapp.module.post.mapper.PostMapper;
import cn.jualn.miniapp.module.activity.mapper.ActivityMapper;
import cn.jualn.miniapp.module.publicevent.mapper.PublicEventMapper;
import cn.jualn.miniapp.module.user.bo.UserSimpleBO;
import cn.jualn.miniapp.module.user.service.UserService;
import cn.jualn.miniapp.third.cos.service.CosService;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.mapstruct.factory.Mappers;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real MySQL + Mapper + Spring transactions + MVC. Auth, safety providers, COS, notifications and Redis are doubles. */
@EnabledIfSystemProperty(named="event.test.jdbcUrl",matches="jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/article_contract_test")
class ArticleDatabaseTest {
 AnnotationConfigApplicationContext context; ArticleService articles; CanonicalCommentService comments; InteractionLikes interactionLikes; InteractionLikeQueries interactionLikeQueries; InteractionCounts interactionCounts; InteractionShares interactionShares; InteractionViews interactionViews;
 JdbcTemplate jdbc; MockMvc mvc; ArticleSafetyChecks safety; NotifyService notifications;
 Class<?> configuration() {return Config.class;}
 static void initializeIdentity() {
  var storage=mock(cn.dev33.satoken.context.model.SaStorage.class);Map<String,Object> values=new HashMap<>();
  when(storage.get(anyString())).thenAnswer(i->values.get(i.getArgument(0)));when(storage.set(anyString(),any())).thenAnswer(i->{values.put(i.getArgument(0),i.getArgument(1));return storage;});when(storage.delete(anyString())).thenAnswer(i->{values.remove(i.getArgument(0));return storage;});
  cn.dev33.satoken.SaManager.getSaTokenContext().setContext(mock(cn.dev33.satoken.context.model.SaRequest.class),mock(cn.dev33.satoken.context.model.SaResponse.class),storage);
  cn.jualn.miniapp.common.security.AdminStpUtil.STP_LOGIC.switchTo(9L);
 }
 @BeforeEach void setup() {
  initializeIdentity();
  context=new AnnotationConfigApplicationContext(configuration()); articles=context.getBean(ArticleService.class); comments=context.getBean(CanonicalCommentService.class);
  interactionLikes=context.getBean(InteractionLikes.class); interactionLikeQueries=context.getBean(InteractionLikeQueries.class); interactionCounts=context.getBean(InteractionCounts.class); interactionShares=context.getBean(InteractionShares.class); interactionViews=context.getBean(InteractionViews.class); safety=context.getBean(ArticleSafetyChecks.class); notifications=context.getBean(NotifyService.class);
  jdbc=new JdbcTemplate(context.getBean(DataSource.class));
  jdbc.execute("DROP TRIGGER IF EXISTS article_test_fail_link");
  jdbc.update("DELETE FROM article_publisher_migration_evidence"); jdbc.update("DELETE FROM article_organization_audit"); jdbc.update("DELETE FROM article_publisher_organization_mapping");
  jdbc.update("DELETE FROM event_attachment_link WHERE target_type=8"); jdbc.update("DELETE FROM article"); jdbc.update("DELETE FROM article_category WHERE parent_id IS NOT NULL"); jdbc.update("DELETE FROM article_category WHERE scene_codes IS NULL OR JSON_LENGTH(scene_codes)=0"); jdbc.update("UPDATE article_category SET enabled=TRUE"); jdbc.update("DELETE FROM article_publisher");
  for(String table:List.of("comment","like_record","share_record","view_log")) jdbc.update("DELETE FROM "+table+" WHERE target_type=8");
  jdbc.update("DELETE FROM media_upload_record WHERE target_type=8"); jdbc.update("DELETE FROM media_attachment WHERE object_key LIKE 'article/retained/%'");
  jdbc.update("INSERT INTO article_publisher VALUES(1,'官方发布主体',1,9,NOW(6))");
  if(jdbc.queryForObject("SELECT COUNT(*) FROM person_organization WHERE id='synthetic-article-org'",Integer.class)==0) {
   jdbc.update("INSERT IGNORE INTO user_profile(id,openid,status) VALUES(9,'article-owner-fixture',1)");
   String event=jdbc.query("SELECT id FROM person_foundation_event WHERE aggregate_type='ORGANIZATION' AND aggregate_id='synthetic-article-org' AND aggregate_version=1",r->r.next()?r.getString(1):null);
   if(event==null) {event=UUID.randomUUID().toString();
   jdbc.update("INSERT INTO person_foundation_event(id,aggregate_type,aggregate_id,aggregate_version,operation,after_json,operator_user_id,recorded_at,reason,verification_reference,source,receipt_id) VALUES(?,'ORGANIZATION','synthetic-article-org',1,'createPersonOrganization','{}',9,UTC_TIMESTAMP(6),'test','synthetic','LOCAL_MANUAL',?)",event,UUID.randomUUID().toString());}
   jdbc.update("INSERT INTO person_organization(id,name,description,status,revision,created_at,updated_at,last_change_id) VALUES('synthetic-article-org','组织署名','','ACTIVE',1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),?)",event);
  }
  UserContext.setUserId(42L);
  var json=new ObjectMapper().addMixIn(org.springframework.http.ProblemDetail.class,org.springframework.http.converter.json.ProblemDetailJacksonMixin.class);
  var converter=new ArticleConverter();
  mvc=MockMvcBuilders.standaloneSetup(new ArticleController(articles,converter),new AdminArticleController(articles,converter),
   new AdminArticleTaxonomyController(articles,converter),new AdminArticleMediaController(context.getBean(ArticleAuthorization.class),context.getBean(MediaService.class)),
   new CanonicalInteractionController(interactionCounts, interactionLikeQueries, interactionLikes, interactionShares, interactionViews, articles, converter, comments, context.getBean(CommentTargetAccess.class)))
   .setControllerAdvice(new GlobalExceptionHandler()).setMessageConverters(new MappingJackson2HttpMessageConverter(json)).build();
 }
 @AfterEach void close() { cn.dev33.satoken.SaManager.getSaTokenContext().clearContext();UserContext.clear(); if(jdbc!=null) jdbc.execute("DROP TRIGGER IF EXISTS article_test_fail_link"); if(context!=null) context.close(); }
 ArticleSaveBO command(Long category,Long cover,String text,Boolean enabled) {
  var json=context.getBean(ObjectMapper.class); var body=json.createObjectNode().put("format","ARTICLE_RICH_TEXT").put("version",1);
  var nodes=body.putArray("nodes"); if(!text.isEmpty()) nodes.addObject().put("type","PARAGRAPH").putArray("runs").addObject().put("text",text);
  return new ArticleSaveBO("党建资讯","摘要",category,1L,cover,body,enabled);
 }
 long category(boolean enabled) { return articles.createCategory(new ArticleCategorySaveBO("学习",0,enabled)).id(); }
 String etag(ArticleBO a) { return StrongEtag.of("article",a.id(),a.revision()); }
 ArticleBO published() { var draft=articles.create(command(category(true),null,"正文",null)); return articles.publish(draft.id(),etag(draft)); }

 @Test void twoLevelsAreImmutableAndOldRequestsKeepTheirMeaning() throws Exception {
  var root=articles.createCategory(new ArticleCategorySaveBO("Root",7,true));
  var child=articles.createCategory(new ArticleCategorySaveBO("Child",2,true,root.id()));
  assertNull(root.parentId()); assertEquals(root.id(),child.parentId());
  mvc.perform(get("/v1/admin/article-categories/"+child.id())).andExpect(status().isOk()).andExpect(jsonPath("$.parentId").value(root.id().toString()));
  mvc.perform(get("/v1/admin/article-categories/"+root.id())).andExpect(status().isOk()).andExpect(jsonPath("$.parentId").doesNotExist());
  for(long parent:new long[]{child.id(),999999L}) {
   var error=assertThrows(ContractProblemException.class,()->articles.createCategory(new ArticleCategorySaveBO("Invalid",0,true,parent)));
   assertEquals(400,error.getStatus().value()); assertEquals("#/parentId",error.getErrors().get(0).pointer());
  }
  for(long parent:new long[]{child.id(),root.id()}) {
   assertEquals(400,assertThrows(ContractProblemException.class,()->articles.replaceCategory(root.id(),new ArticleCategorySaveBO("Changed",0,true,parent),StrongEtag.of("article-category",root.id(),root.revision()))).getStatus().value());
  }
  assertEquals("Root",articles.category(root.id()).name());
  var changed=articles.replaceCategory(child.id(),new ArticleCategorySaveBO("Renamed",33,false),StrongEtag.of("article-category",child.id(),child.revision()));
  assertEquals(root.id(),changed.parentId()); assertEquals(33,changed.displayOrder()); assertFalse(changed.enabled());
  var same=articles.replaceCategory(child.id(),new ArticleCategorySaveBO("Renamed",1,true,root.id()),StrongEtag.of("article-category",child.id(),changed.revision()));
  assertTrue(same.enabled());
  mvc.perform(put("/v1/admin/article-categories/"+child.id()).header("If-Match",StrongEtag.of("article-category",child.id(),same.revision()))
   .contentType("application/json").content("{\"name\":\"Changed\",\"displayOrder\":0,\"enabled\":true,\"parentId\":null}"))
   .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].pointer").value("#/parentId"));
  assertEquals("Renamed",articles.category(child.id()).name());
  assertEquals("/problems/category-name-conflict",assertThrows(ContractProblemException.class,()->articles.createCategory(new ArticleCategorySaveBO(" Root ",0,true,root.id()))).getType());
 }
 @Test void parentDisableHidesNavigationButPreservesHistoryAndDraftEditing() throws Exception {
  var root=articles.createCategory(new ArticleCategorySaveBO("Root",0,true));
  var child=articles.createCategory(new ArticleCategorySaveBO("Child",0,true,root.id()));
  var draft=articles.create(command(child.id(),null,"Historical child",null)); var live=articles.publish(draft.id(),etag(draft));
  var rootDraft=articles.create(command(root.id(),null,"Historical root",null)); var rootLive=articles.publish(rootDraft.id(),etag(rootDraft));
  var disabled=articles.replaceCategory(root.id(),new ArticleCategorySaveBO("Root disabled",1,false),StrongEtag.of("article-category",root.id(),root.revision()));
  assertTrue(articles.categories(false).stream().noneMatch(category -> category.sceneCodes().isEmpty())); assertEquals(2,articles.categories(true).stream().filter(c -> c.sceneCodes().isEmpty()).count()); assertTrue(articles.category(child.id()).enabled());
  assertEquals(root.id(),articles.getPublic(live.id()).category().parentId()); assertEquals(live.revision(),articles.getAdmin(live.id()).revision());
  assertEquals(2,articles.listPublic(root.id(),true,null,20,"-publishedAt").items().size());
  assertEquals(409,assertThrows(ContractProblemException.class,()->articles.replace(live.id(),command(child.id(),null,"Changed",null),etag(live))).getStatus().value());
  var hidden=articles.unpublish(live.id(),etag(live)); var edited=articles.replace(hidden.id(),command(child.id(),null,"Draft allowed",null),etag(hidden));
  assertEquals(409,assertThrows(ContractProblemException.class,()->articles.publish(edited.id(),etag(edited))).getStatus().value());
  assertEquals("Historical root",articles.getPublic(rootLive.id()).plainText());
  // Disabled parents are legal directory parents, but never publication eligibility.
  assertNotNull(articles.createCategory(new ArticleCategorySaveBO("New disabled-parent child",0,true,root.id())).id());
  articles.replaceCategory(child.id(),new ArticleCategorySaveBO("Child",0,false),StrongEtag.of("article-category",child.id(),child.revision()));
  articles.replaceCategory(root.id(),new ArticleCategorySaveBO("Root restored",0,true),StrongEtag.of("article-category",root.id(),disabled.revision()));
  assertFalse(articles.categories(false).stream().anyMatch(c->c.id().equals(child.id())));
  assertEquals("Draft allowed",articles.getAdmin(edited.id()).plainText());
 }
 @Test void explicitChildrenFilterBindsCursorAndCountsBeforePagination() throws Exception {
  var root=articles.createCategory(new ArticleCategorySaveBO("Root",0,true));
  var child=articles.createCategory(new ArticleCategorySaveBO("Child",0,true,root.id()));
  var other=articles.createCategory(new ArticleCategorySaveBO("Other",0,true));
  for(long category:new long[]{root.id(),root.id(),child.id(),child.id(),other.id()}) {
   var draft=articles.create(command(category,null,"Visible",null)); articles.publish(draft.id(),etag(draft));
  }
  articles.create(command(child.id(),null,"Hidden draft",null));
  assertEquals(2,articles.listPublic(root.id(),null,20,"-publishedAt").items().size());
  assertEquals(4,articles.listPublic(root.id(),true,null,20,"-publishedAt").items().size());
  assertEquals(2,articles.listPublic(child.id(),true,null,20,"-publishedAt").items().size());
  var first=articles.listPublic(root.id(),true,null,2,"-publishedAt");
  var second=articles.listPublic(root.id(),true,first.nextCursor(),2,"-publishedAt");
  assertNull(second.nextCursor()); var ids=new HashSet<Long>(); first.items().forEach(a->ids.add(a.id())); second.items().forEach(a->ids.add(a.id())); assertEquals(4,ids.size());
  assertEquals("/problems/invalid-cursor",assertThrows(ContractProblemException.class,()->articles.listPublic(root.id(),false,first.nextCursor(),2,"-publishedAt")).getType());
  var exact=articles.listPublic(root.id(),null,1,"-publishedAt"); assertEquals(1,articles.listPublic(root.id(),false,exact.nextCursor(),1,"-publishedAt").items().size());
  var admin=articles.listAdmin(2,2,null,root.id(),true,null,null,"-updatedAt"); assertEquals(5,admin.totalItems()); assertEquals(2,admin.items().size());
  mvc.perform(get("/v1/articles").param("categoryId",root.id().toString()).param("includeChildren","true")).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(4));
  mvc.perform(get("/v1/admin/articles").param("categoryId",root.id().toString()).param("includeChildren","true").param("publishStatus","PUBLISHED")).andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(4));
  for(String path:List.of("/v1/articles","/v1/admin/articles")) {
   for(String value:List.of("true","false")) mvc.perform(get(path).param("includeChildren",value)).andExpect(status().isBadRequest());
   mvc.perform(get(path).param("categoryId","999999")).andExpect(status().isNotFound());
   for(String invalid:List.of("invalid","","yes","1")) mvc.perform(get(path).param("categoryId",root.id().toString()).param("includeChildren",invalid)).andExpect(status().isBadRequest());
  }
 }
 @Test void publicationRechecksParentAfterSafetyAndRollsBackOnlineEdit() {
  var root=articles.createCategory(new ArticleCategorySaveBO("Root",0,true));
  var child=articles.createCategory(new ArticleCategorySaveBO("Child",0,true,root.id()));
  var draft=articles.create(command(child.id(),null,"Original",null));
  doAnswer(call->{ articles.replaceCategory(root.id(),new ArticleCategorySaveBO("Root",0,false),StrongEtag.of("article-category",root.id(),root.revision())); return null; }).when(safety).text(eq(9L),contains("Original"));
  assertEquals("/problems/publish-validation-failed",assertThrows(ContractProblemException.class,()->articles.publish(draft.id(),etag(draft))).getType());
  assertEquals("DRAFT",articles.getAdmin(draft.id()).publishStatus()); assertEquals(draft.revision(),articles.getAdmin(draft.id()).revision());
 }
 @Test void categoryEtagAndConcurrentCreatorsRemainSerialized() throws Exception {
  var root=articles.createCategory(new ArticleCategorySaveBO("Root",0,true));
  var pool=Executors.newFixedThreadPool(2); var start=new CountDownLatch(1);
  try {
   List<Future<Integer>> writes=new ArrayList<>();
   for(int n=0;n<2;n++) writes.add(pool.submit(()->{ start.await(); try { articles.replaceCategory(root.id(),new ArticleCategorySaveBO("Edited",9,false),StrongEtag.of("article-category",root.id(),root.revision())); return 200; } catch(ContractProblemException e) { return e.getStatus().value(); } }));
   start.countDown(); var statuses=new ArrayList<Integer>(); for(var f:writes) statuses.add(f.get(10,TimeUnit.SECONDS)); Collections.sort(statuses); assertEquals(List.of(200,412),statuses);
   var createStart=new CountDownLatch(1); writes.clear();
   for(int n=0;n<2;n++) writes.add(pool.submit(()->{ createStart.await(); try { articles.createCategory(new ArticleCategorySaveBO("Concurrent child",0,true,root.id())); return 201; } catch(ContractProblemException e) { return e.getStatus().value(); } }));
   createStart.countDown(); statuses.clear(); for(var f:writes) statuses.add(f.get(10,TimeUnit.SECONDS)); Collections.sort(statuses); assertEquals(List.of(201,409),statuses);
   assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM article_category WHERE parent_id=?",Integer.class,root.id()));
  } finally { pool.shutdownNow(); }
 }

 @Test void concurrentChildCreationAndParentDisableKeepIndependentStates() throws Exception {
  var root=articles.createCategory(new ArticleCategorySaveBO("Root",0,true));
  var pool=Executors.newFixedThreadPool(2); var start=new CountDownLatch(1);
  try {
   var create=pool.submit(()->{start.await(); return articles.createCategory(new ArticleCategorySaveBO("Child",0,true,root.id()));});
   var disable=pool.submit(()->{start.await(); return articles.replaceCategory(root.id(),new ArticleCategorySaveBO("Root",0,false),StrongEtag.of("article-category",root.id(),root.revision()));});
   start.countDown(); var child=create.get(10,TimeUnit.SECONDS); assertFalse(disable.get(10,TimeUnit.SECONDS).enabled());
   assertTrue(articles.category(child.id()).enabled()); assertTrue(articles.categories(false).stream().noneMatch(category -> category.sceneCodes().isEmpty()));
   var draft=articles.create(command(child.id(),null,"Blocked",null));
   assertEquals("/problems/publish-validation-failed",assertThrows(ContractProblemException.class,()->articles.publish(draft.id(),etag(draft))).getType());
  } finally {pool.shutdownNow();}
 }
 @Test void concurrentCreationAtDirectoryLimitCannotExceedOneHundred() throws Exception {
  long root=category(true);
  for(int n=0;n<98;n++) jdbc.update("INSERT INTO article_category(name,display_order,enabled,created_at,updated_at,created_by,updated_by) VALUES(?,0,1,NOW(6),NOW(6),9,9)","limit-"+n);
  var pool=Executors.newFixedThreadPool(2); var start=new CountDownLatch(1);
  try {
   List<Future<Integer>> attempts=new ArrayList<>();
   for(String name:List.of("Last A","Last B")) attempts.add(pool.submit(()->{start.await(); try {articles.createCategory(new ArticleCategorySaveBO(name,0,true,root)); return 201;} catch(ContractProblemException e) {assertEquals("/problems/category-limit-reached",e.getType()); return e.getStatus().value();}}));
   start.countDown(); var statuses=new ArrayList<Integer>(); for(var f:attempts) statuses.add(f.get(10,TimeUnit.SECONDS)); Collections.sort(statuses); assertEquals(List.of(201,409),statuses);
   assertEquals(100,jdbc.queryForObject("SELECT COUNT(*) FROM article_category",Integer.class));
  } finally {pool.shutdownNow();}
 }
 @Test void onlineEditRechecksDisabledParentAfterCandidateSafety() {
  var root=articles.createCategory(new ArticleCategorySaveBO("Root",0,true));
  var child=articles.createCategory(new ArticleCategorySaveBO("Child",0,true,root.id()));
  var draft=articles.create(command(child.id(),null,"Original",null)); var live=articles.publish(draft.id(),etag(draft));
  doAnswer(call->{articles.replaceCategory(root.id(),new ArticleCategorySaveBO("Root",0,false),StrongEtag.of("article-category",root.id(),root.revision())); return null;}).when(safety).text(eq(9L),contains("Candidate edit"));
  assertEquals("/problems/publish-validation-failed",assertThrows(ContractProblemException.class,()->articles.replace(live.id(),command(child.id(),null,"Candidate edit",null),etag(live))).getType());
  assertEquals("Original",articles.getPublic(live.id()).plainText()); assertEquals(live.revision(),articles.getAdmin(live.id()).revision());
 }
 @Test void hierarchyDoesNotBypassDirectoryAndArticlePermissions() throws Exception {
  var auth=context.getBean(ArticleAuthorization.class);
  for(var capability:List.of(ArticleAuthorization.Capability.CATEGORY_READ,ArticleAuthorization.Capability.CATEGORY_MANAGE,ArticleAuthorization.Capability.READ)) {
   when(auth.require(capability)).thenThrow(new ContractProblemException(org.springframework.http.HttpStatus.FORBIDDEN,"/problems/forbidden","Denied"));
  }
  mvc.perform(get("/v1/admin/article-categories")).andExpect(status().isForbidden());
  mvc.perform(post("/v1/admin/article-categories").contentType("application/json").content("{\"name\":\"Child\",\"displayOrder\":0,\"enabled\":true,\"parentId\":\"1\"}")).andExpect(status().isForbidden());
  mvc.perform(get("/v1/admin/articles").param("categoryId","1").param("includeChildren","true")).andExpect(status().isForbidden());
  assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM article_category",Integer.class));
 }
 @Test void completeLifecyclePreservesFirstPublicationAndPostIsolation() throws Exception {
  long posts=jdbc.queryForObject("SELECT COUNT(*) FROM post",Long.class);
  String oldContent=jdbc.queryForObject("SELECT content FROM post WHERE user_id=(SELECT id FROM user_profile WHERE openid='m01-migration-legacy')",String.class);
  var draft=articles.create(command(null,null,"",null)); assertTrue(draft.commentsEnabled()); assertEquals("DRAFT",draft.publishStatus());
  assertEquals(404,assertThrows(ContractProblemException.class,()->articles.getPublic(draft.id())).getStatus().value());
  assertEquals(409,assertThrows(ContractProblemException.class,()->articles.publish(draft.id(),etag(draft))).getStatus().value());
  var edited=articles.replace(draft.id(),command(category(true),null,"初版",false),etag(draft));
  var published=articles.publish(edited.id(),etag(edited)); assertEquals("初版",articles.getPublic(draft.id()).plainText());
  var online=articles.replace(published.id(),command(published.categoryId(),null,"在线编辑",null),etag(published)); assertFalse(online.commentsEnabled());
  assertEquals("在线编辑",articles.getPublic(draft.id()).plainText());
  var hidden=articles.unpublish(online.id(),etag(online));
  assertEquals(404,assertThrows(ContractProblemException.class,()->articles.getPublic(hidden.id())).getStatus().value());
  var again=articles.publish(hidden.id(),etag(hidden)); assertEquals(published.publishedAt(),again.publishedAt()); assertFalse(again.lastPublishedAt().isBefore(published.lastPublishedAt()));
  assertEquals(409,assertThrows(ContractProblemException.class,()->articles.publish(again.id(),etag(again))).getStatus().value());
  assertEquals(posts,jdbc.queryForObject("SELECT COUNT(*) FROM post",Long.class));
  assertEquals(oldContent,jdbc.queryForObject("SELECT content FROM post WHERE user_id=(SELECT id FROM user_profile WHERE openid='m01-migration-legacy')",String.class));
  mvc.perform(get("/v1/articles/"+again.id())).andExpect(status().isOk()).andExpect(jsonPath("$.body.version").value(1)).andExpect(jsonPath("$.publisherOrgId").value("synthetic-article-org"));
  mvc.perform(get("/v1/admin/articles/"+again.id())).andExpect(status().isOk()).andExpect(header().string("ETag",etag(again))).andExpect(jsonPath("$.createdBy.identityType").value("ADMIN")).andExpect(jsonPath("$.statistics").doesNotExist());
 }
 @Test void strongPreconditionsAndConcurrentEditorsHaveOneWinner() throws Exception {
  var draft=articles.create(command(null,null,"",null));
  assertEquals(428,assertThrows(ContractProblemException.class,()->articles.replace(draft.id(),command(null,null,"新",null),null)).getStatus().value());
  assertEquals(400,assertThrows(ContractProblemException.class,()->articles.replace(draft.id(),command(null,null,"新",null),"W/"+etag(draft))).getStatus().value());
  ExecutorService pool=Executors.newFixedThreadPool(2); CountDownLatch start=new CountDownLatch(1);
  Callable<Integer> edit=()-> { initializeIdentity();start.await(); try { articles.replace(draft.id(),command(null,null,"并发",null),etag(draft)); return 200; } catch(ContractProblemException e) { return e.getStatus().value(); } finally {cn.dev33.satoken.SaManager.getSaTokenContext().clearContext();} };
  try { var a=pool.submit(edit); var b=pool.submit(edit); start.countDown(); assertEquals(Set.of(200,412),Set.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS))); }
  finally { pool.shutdownNow(); }
 }
 @Test void disablingCategoryRetainsHistoryButBlocksRepublishAndOnlineEdits() {
  var article=published(); var c=articles.category(article.categoryId());
  var disabled=articles.replaceCategory(c.id(),new ArticleCategorySaveBO("学习更名",10,false),StrongEtag.of("article-category",c.id(),c.revision()));
  assertFalse(disabled.enabled()); assertTrue(articles.categories(false).stream().noneMatch(category -> category.sceneCodes().isEmpty())); assertEquals("学习更名",articles.getPublic(article.id()).category().name());
  assertEquals(1,articles.listPublic(c.id(),null,20,"-publishedAt").items().size());
  assertEquals(409,assertThrows(ContractProblemException.class,()->articles.replace(article.id(),command(c.id(),null,"更新",null),etag(article))).getStatus().value());
  var hidden=articles.unpublish(article.id(),etag(article)); assertEquals(409,assertThrows(ContractProblemException.class,()->articles.publish(hidden.id(),etag(hidden))).getStatus().value());
  assertEquals(409,assertThrows(ContractProblemException.class,()->articles.createCategory(new ArticleCategorySaveBO(" 学习更名 ",0,true))).getStatus().value());
  articles.createCategory(new ArticleCategorySaveBO("A",0,true)); articles.createCategory(new ArticleCategorySaveBO("a",0,true));
 }
 @Test void safetyFailureAndDatabaseFailureLeaveTheOldRepresentationAndReferences() {
  long image=verifiedImage(); var article=articles.create(command(category(true),image,"正文",null));
  doThrow(ArticleSafetyChecks.unavailable()).when(safety).text(anyLong(),anyString());
  assertEquals(503,assertThrows(ContractProblemException.class,()->articles.publish(article.id(),etag(article))).getStatus().value());
  assertEquals("DRAFT",articles.getAdmin(article.id()).publishStatus()); reset(safety);
  jdbc.execute("CREATE TRIGGER article_test_fail_link BEFORE INSERT ON event_attachment_link FOR EACH ROW BEGIN IF NEW.target_type=8 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic Article link failure'; END IF; END");
  assertThrows(RuntimeException.class,()->articles.replace(article.id(),command(article.categoryId(),image,"不能生效",null),etag(article)));
  assertEquals("正文",articles.getAdmin(article.id()).plainText()); assertEquals(article.revision(),articles.getAdmin(article.id()).revision());
  assertEquals(image,jdbc.queryForObject("SELECT attachment_id FROM event_attachment_link WHERE target_type=8 AND target_id=?",Long.class,article.id()));
  assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM media_attachment WHERE id=?",Integer.class,image));
 }
 @Test void mediaReferencesAreRetainedAcrossDraftUnpublishAndSharedReplacement() {
  long image=verifiedImage(); long c=category(true);
  var first=articles.create(command(c,image,"第一篇",null)); var second=articles.create(command(c,image,"第二篇",null));
  var read=context.getBean(AttachmentReadService.class);
  assertThrows(BusinessException.class,()->read.getPublicAttachment(image));
  first=articles.publish(first.id(),etag(first)); assertEquals(image,read.getPublicAttachment(image).getId());
  articles.replace(second.id(),command(c,null,"移除",null),etag(second)); assertEquals(image,read.getPublicAttachment(image).getId());
  articles.unpublish(first.id(),etag(first)); assertThrows(BusinessException.class,()->read.getPublicAttachment(image));
  assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM media_attachment WHERE id=?",Integer.class,image));
  jdbc.update("UPDATE media_attachment SET verified=0 WHERE id=?",image);
  assertThrows(ContractProblemException.class,()->articles.create(command(c,image,"错误资源",null)));
 }
 @Test void commentsToggleThreadsLikesAndDailyViewsUseIndependentFacts() {
  var article=published(); var root=comments.create(article.id(),new ArticleCommentCreateBO("顶层",null,null));
  verifyNoInteractions(notifications);
  UserContext.setUserId(43L); var reply=comments.create(article.id(),new ArticleCommentCreateBO("回复",root.id(),root.id()));
  var capture=org.mockito.ArgumentCaptor.forClass(NotifyPayload.class); verify(notifications).processNotificationPayload(capture.capture());
  assertEquals(42L,capture.getValue().getReceiverId()); assertEquals(cn.jualn.miniapp.module.notify.enums.NotifyType.COMMENT_REPLIED,capture.getValue().getType()); assertEquals("COMMENT",capture.getValue().getSnapshot().subject().type());
  assertEquals("ARTICLE_DETAIL",capture.getValue().getSnapshot().target().type());
  interactionLikes.like(TargetType.ARTICLE,article.id()); interactionLikes.like(TargetType.ARTICLE,article.id()); assertEquals(1,articles.statistics(article.id()).likeCount());
  assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM like_record WHERE target_type=8",Integer.class));
  interactionViews.view(TargetType.ARTICLE,article.id()); interactionViews.view(TargetType.ARTICLE,article.id()); assertEquals(1,articles.statistics(article.id()).viewCount());
  interactionShares.share(TargetType.ARTICLE,article.id(),1); assertEquals(1,articles.statistics(article.id()).shareCount());
  assertEquals(2,articles.statistics(article.id()).commentCount());
  var disabled=articles.replace(article.id(),command(article.categoryId(),null,"正文",false),etag(article));
  assertEquals(1,comments.list(article.id(),null,null,20).items().size());
  assertEquals(409,assertThrows(ContractProblemException.class,()->comments.create(article.id(),new ArticleCommentCreateBO("不能新增",root.id(),null))).getStatus().value());
  interactionLikes.like(TargetType.COMMENT,root.id());
  UserContext.setUserId(42L); comments.delete(article.id(),root.id()); comments.delete(article.id(),root.id());
  assertEquals(0,comments.list(article.id(),null,null,20).items().size()); assertEquals(0,articles.statistics(article.id()).commentCount());
  assertEquals(404,assertThrows(ContractProblemException.class,()->interactionLikes.like(TargetType.COMMENT,reply.id())).getStatus().value());
  var hidden=articles.unpublish(article.id(),etag(disabled));
  assertEquals(404,assertThrows(ContractProblemException.class,()->interactionViews.view(TargetType.ARTICLE,hidden.id())).getStatus().value());
  assertEquals(404,assertThrows(ContractProblemException.class,()->comments.list(hidden.id(),null,null,20)).getStatus().value());
  UserContext.setUserId(43L); comments.delete(hidden.id(),reply.id());
 }
 @Test void rejectCrossArticleRepliesAndRejectedCommentsWithoutFacts() {
  var a=published(); var root=comments.create(a.id(),new ArticleCommentCreateBO("顶层",null,null));
  var draft=articles.create(command(a.categoryId(),null,"另一篇",null)); var b=articles.publish(draft.id(),etag(draft));
  assertEquals(400,assertThrows(ContractProblemException.class,()->comments.create(b.id(),new ArticleCommentCreateBO("跨文章",root.id(),null))).getStatus().value());
  doThrow(ContractProblemException.conflict("content-rejected","synthetic rejected")).when(safety).text(anyLong(),anyString());
  assertEquals(400,assertThrows(ContractProblemException.class,()->comments.create(a.id(),new ArticleCommentCreateBO("拒绝",null,null))).getStatus().value());
  assertEquals(1,articles.statistics(a.id()).commentCount());
 }
 @Test void unauthorizedManagementIsDeniedAndMvcDraftRepresentationHasExplicitNullCategory() throws Exception {
  var draft=articles.create(command(null,null,"",null));
  mvc.perform(get("/v1/admin/articles/"+draft.id())).andExpect(status().isOk()).andExpect(jsonPath("$.categoryId").value(org.hamcrest.Matchers.nullValue()));
  doThrow(new ContractProblemException(org.springframework.http.HttpStatus.FORBIDDEN,"/problems/forbidden","synthetic denied"))
   .when(context.getBean(ArticleAuthorization.class)).require(any());
  mvc.perform(get("/v1/admin/articles/"+draft.id())).andExpect(status().isForbidden());
  mvc.perform(post("/v1/admin/articles").contentType("application/json").content("{\"title\":\"\",\"summary\":\"\",\"categoryId\":null,\"publisherId\":\"1\",\"body\":{\"format\":\"ARTICLE_RICH_TEXT\",\"version\":1,\"nodes\":[]}}"))
   .andExpect(status().isForbidden());
 }
 @Test void canonicalHttpOperationsProduceContractRepresentations() throws Exception {
  var fixtures=new ArrayList<Map<String,Object>>();
  var json=context.getBean(ObjectMapper.class);
  var categoryResponse=mvc.perform(post("/v1/admin/article-categories").contentType("application/json").content("{\"name\":\"栏目\",\"displayOrder\":3,\"enabled\":true}")).andExpect(status().isCreated()).andReturn().getResponse();
  record(fixtures,"ArticleCategory",categoryResponse); String categoryId=json.readTree(categoryResponse.getContentAsByteArray()).get("id").asText();
  record(fixtures,"ArticleCategoryList",mvc.perform(get("/v1/admin/article-categories")).andExpect(status().isOk()).andReturn().getResponse());
  record(fixtures,"ArticleCategory",mvc.perform(get("/v1/admin/article-categories/"+categoryId)).andExpect(status().isOk()).andReturn().getResponse());
  record(fixtures,"ArticleCategory",mvc.perform(put("/v1/admin/article-categories/"+categoryId).header("If-Match",categoryResponse.getHeader("ETag")).contentType("application/json").content("{\"name\":\"更新栏目\",\"displayOrder\":2,\"enabled\":true}")).andExpect(status().isOk()).andReturn().getResponse());
  var childResponse=mvc.perform(post("/v1/admin/article-categories").contentType("application/json")
   .content("{\"name\":\"Child fixture\",\"displayOrder\":1,\"enabled\":true,\"parentId\":\""+categoryId+"\"}")).andExpect(status().isCreated()).andReturn().getResponse();
  record(fixtures,"ArticleCategory",childResponse);
  String childId=json.readTree(childResponse.getContentAsByteArray()).get("id").asText();
  record(fixtures,"ArticlePublisherList",mvc.perform(get("/v1/admin/article-publishers")).andExpect(status().isOk()).andReturn().getResponse());
  var publisher=mvc.perform(get("/v1/admin/article-publishers/1")).andExpect(status().isOk()).andReturn().getResponse(); record(fixtures,"ArticlePublisher",publisher);
  record(fixtures,"ArticlePublisher",mvc.perform(put("/v1/admin/article-publishers/1").header("If-Match",publisher.getHeader("ETag")).contentType("application/json").content("{\"displayName\":\"校党委\"}")).andExpect(status().isOk()).andReturn().getResponse());
  var request=json.valueToTree(command(Long.parseLong(childId),null,"正文检索词",null)); request=((com.fasterxml.jackson.databind.node.ObjectNode)request).put("categoryId",childId).put("publisherId","1");
  ((com.fasterxml.jackson.databind.node.ObjectNode)request).remove(List.of("coverAttachmentId","commentsEnabled","publisherOrgId"));
  var created=mvc.perform(post("/v1/admin/articles").contentType("application/json").content(request.toString())).andExpect(status().isCreated()).andExpect(header().exists("Location")).andReturn().getResponse();
  record(fixtures,"ArticleAdminDetail",created); String id=json.readTree(created.getContentAsByteArray()).get("id").asText();
  mvc.perform(get("/v1/articles/"+id)).andExpect(status().isNotFound());
  mvc.perform(get("/v1/articles/not-allocated")).andExpect(status().isNotFound());
  mvc.perform(get("/v1/articles").param("categoryId","unknown-category")).andExpect(status().isNotFound());
  var invalidReference=((com.fasterxml.jackson.databind.node.ObjectNode)request).deepCopy().put("publisherId","unknown-publisher");
  mvc.perform(post("/v1/admin/articles").contentType("application/json").content(invalidReference.toString())).andExpect(status().isConflict())
   .andExpect(jsonPath("$.type").value("/problems/article-publisher-migration-required"));
  var replaced=mvc.perform(put("/v1/admin/articles/"+id).header("If-Match",created.getHeader("ETag")).contentType("application/json").content(request.toString())).andExpect(status().isOk()).andReturn().getResponse(); record(fixtures,"ArticleAdminDetail",replaced);
  record(fixtures,"ArticleAdminDetail",mvc.perform(get("/v1/admin/articles/"+id)).andExpect(status().isOk()).andReturn().getResponse());
  record(fixtures,"ArticleAdminPage",mvc.perform(get("/v1/admin/articles").param("q","正文检索词").param("page","1").param("sort","-createdAt")).andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(1)).andReturn().getResponse());
  var published=mvc.perform(post("/v1/admin/articles/"+id+":publish").header("If-Match",replaced.getHeader("ETag"))).andExpect(status().isOk()).andReturn().getResponse(); record(fixtures,"ArticleAdminDetail",published);
  record(fixtures,"ArticleDetail",mvc.perform(get("/v1/articles/"+id)).andExpect(status().isOk()).andExpect(jsonPath("$.statistics.viewCount").value(0)).andReturn().getResponse());
  record(fixtures,"ArticlePage",mvc.perform(get("/v1/articles").param("categoryId",categoryId)).andExpect(status().isOk()).andReturn().getResponse());
  record(fixtures,"ArticleCategoryList",mvc.perform(get("/v1/article-categories")).andExpect(status().isOk()).andReturn().getResponse());
  String target="/v1/interaction-targets/ARTICLE/"+id;
  record(fixtures,"InteractionLikeState",mvc.perform(get(target+"/like")).andExpect(status().isOk()).andReturn().getResponse());
  record(fixtures,"InteractionLikeState",mvc.perform(put(target+"/like")).andExpect(status().isOk()).andExpect(jsonPath("$.liked").value(true)).andReturn().getResponse());
  record(fixtures,"InteractionLikeState",mvc.perform(delete(target+"/like")).andExpect(status().isOk()).andExpect(jsonPath("$.liked").value(false)).andReturn().getResponse());
  var root=mvc.perform(post(target+"/comments").contentType("application/json").content("{\"content\":\"评论\"}")).andExpect(status().isCreated()).andReturn().getResponse(); record(fixtures,"InteractionComment",root);
  String commentId=json.readTree(root.getContentAsByteArray()).get("id").asText();
  record(fixtures,"InteractionCommentPage",mvc.perform(get(target+"/comments")).andExpect(status().isOk()).andReturn().getResponse());
  record(fixtures,"InteractionLikeState",mvc.perform(put("/v1/interaction-targets/COMMENT/"+commentId+"/like")).andExpect(status().isOk()).andReturn().getResponse());
  record(fixtures,"ArticleStatistics",mvc.perform(post(target+"/views")).andExpect(status().isOk()).andExpect(jsonPath("$.viewCount").value(1)).andReturn().getResponse());
  record(fixtures,"ArticleStatistics",mvc.perform(post(target+"/shares").contentType("application/json").content("{\"platform\":\"WECHAT_MOMENTS\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.shareCount").value(1)).andReturn().getResponse());
  assertEquals(published.getHeader("ETag"),mvc.perform(get("/v1/admin/articles/"+id)).andReturn().getResponse().getHeader("ETag"));
  var hidden=mvc.perform(post("/v1/admin/articles/"+id+":unpublish").header("If-Match",published.getHeader("ETag"))).andExpect(status().isOk()).andReturn().getResponse(); record(fixtures,"ArticleAdminDetail",hidden);
  mvc.perform(delete(target+"/comments/"+commentId)).andExpect(status().isNoContent());
  mvc.perform(delete(target+"/comments/"+commentId)).andExpect(status().isNoContent());
  mvc.perform(get(target+"/like")).andExpect(status().isNotFound());
  var cos=context.getBean(CosService.class);
  when(cos.generateExactUploadCredential(anyList())).thenAnswer(i->cn.jualn.miniapp.third.cos.dto.CosUploadCredentialDTO.builder()
    .bucket("synthetic-bucket").region("synthetic-region").objectKeys(i.getArgument(0)).tmpSecretId("synthetic-id")
    .tmpSecretKey("synthetic-key").sessionToken("synthetic-token").expiredTime(java.time.Instant.now().getEpochSecond()+600).build());
  record(fixtures,"ArticleUploadCredentials",mvc.perform(post("/v1/admin/media/article-upload-credentials").contentType("application/json")
   .content("{\"files\":[{\"fileName\":\"封面.png\",\"kind\":\"IMAGE\",\"contentType\":\"image/png\",\"sizeBytes\":100}]}"))
   .andExpect(status().isOk()).andReturn().getResponse());
  java.nio.file.Files.createDirectories(java.nio.file.Path.of("target"));
  json.writerWithDefaultPrettyPrinter().writeValue(java.nio.file.Path.of("target/article-contract-responses.json").toFile(),fixtures);
 }
 @Test void registeredSnapshotBindingRetainsMediaAndRecyclesOnlyTemporaryOriginal() {
  var cos=context.getBean(CosService.class); var media=context.getBean(ArticleMediaService.class);
  when(cos.generateExactUploadCredential(anyList())).thenAnswer(i->cn.jualn.miniapp.third.cos.dto.CosUploadCredentialDTO.builder().objectKeys(i.getArgument(0))
   .tmpSecretId("synthetic").tmpSecretKey("synthetic").sessionToken("synthetic").expiredTime(java.time.Instant.now().getEpochSecond()+600).build());
  var credential=media.upload(List.of(new ArticleUploadFileBO("图片.png","IMAGE","image/png",100)),9);
  String source=credential.getObjectKeys().get(0); String url="https://cdn.example/"+source;
  when(cos.resolveManagedObjectKey(url)).thenReturn(source);
  doAnswer(i->{ assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM media_upload_record WHERE object_key=? AND status=0",Integer.class,(String)i.getArgument(1))); return null; }).when(cos).copyObject(anyString(),anyString());
  when(context.getBean(ManagedArticleMediaInspector.class).inspect(anyString(),eq("IMAGE"),eq("image/png"),eq(100L)))
   .thenReturn(new ManagedArticleMediaInspector.Metadata("image/png",100,2,2,null));
  assertEquals(400,assertThrows(ContractProblemException.class,()->media.register("VIDEO","错误类型",url,9)).getStatus().value());
  var attachment=media.register("IMAGE","图片",url,9);
  assertTrue(attachment.getVerified()); assertEquals("IMAGE",attachment.getKind()); assertTrue(attachment.getObjectKey().startsWith("article/retained/9/"));
  assertEquals(attachment.getId(),jdbc.queryForObject("SELECT bound_attachment_id FROM media_upload_record WHERE object_key=? AND status=0",Long.class,attachment.getObjectKey()));
  assertEquals("CANDIDATE",attachment.getRetention());
  var draft=articles.create(command(null,attachment.getId(),"",null));
  assertEquals("FORMAL",context.getBean(MediaQueries.class).getAttachment(attachment.getId()).getRetention());
  assertEquals(0,jdbc.queryForObject("SELECT status FROM media_upload_record WHERE object_key=?",Integer.class,source));
  jdbc.update("UPDATE media_upload_record SET cleanup_after=NOW()-INTERVAL 1 DAY WHERE target_type=8");
  when(cos.managedPublicUrls(anyString())).thenAnswer(i->List.of("https://cdn.example/"+i.getArgument(0)));
  assertEquals(1,context.getBean(MediaUploadRecordService.class).cleanupExpiredBatch());
  verify(cos).deleteObject(source); verify(cos,never()).deleteObject(attachment.getObjectKey());
  assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM media_attachment WHERE id=?",Integer.class,attachment.getId()));
  assertEquals(1,jdbc.queryForObject("SELECT status FROM media_upload_record WHERE object_key=?",Integer.class,attachment.getObjectKey()));
 }
 @Test void videoRegistrationReservesAuditInSameTransactionAndRollsBackOnReservationFailure() {
  var cos=context.getBean(CosService.class); var media=context.getBean(ArticleMediaService.class);
  var videoSafety=context.getBean(cn.jualn.miniapp.module.audit.service.VideoSafetyChecks.class);
  String source="article/upload/9/video.mp4",url="https://cdn.example/"+source;
  context.getBean(ArticleMediaWriter.class).pending(List.of(source),List.of(new ArticleUploadFileBO("视频.mp4","VIDEO","video/mp4",100)),9);
  when(cos.resolveManagedObjectKey(url)).thenReturn(source);
  when(context.getBean(ManagedArticleMediaInspector.class).inspect(anyString(),eq("VIDEO"),eq("video/mp4"),eq(100L)))
   .thenReturn(new ManagedArticleMediaInspector.Metadata("video/mp4",100,640,480,30d));
  doAnswer(call->{
   assertTrue(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
   assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM media_attachment WHERE id=?",Integer.class,(Long)call.getArgument(0)));
   throw new IllegalStateException("synthetic audit reservation failure");
  }).when(videoSafety).reserve(anyLong(),anyString(),eq(100L),eq(9L));
  assertThrows(IllegalStateException.class,()->media.register("VIDEO","视频",url,9));
  assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM media_attachment WHERE kind='VIDEO'",Integer.class));
  assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM media_upload_record WHERE target_type=8 AND status=1",Integer.class));
  doNothing().when(videoSafety).reserve(anyLong(),anyString(),eq(100L),eq(9L));
  var attachment=media.register("VIDEO","视频",url,9);
  verify(videoSafety).reserve(attachment.getId(),attachment.getObjectKey(),100,9);
  assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM media_upload_record WHERE bound_attachment_id=? AND status=0",Integer.class,attachment.getId()));
  assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM media_upload_record WHERE bound_attachment_id=? AND status=1",Integer.class,attachment.getId()));
  assertEquals("CANDIDATE",attachment.getRetention());
 }
 @Test void failedMediaRegistrationRollsBackBindingButKeepsDurableCleanupResponsibility() {
  var cos=context.getBean(CosService.class); var media=context.getBean(ArticleMediaService.class);
  String source="article/upload/9/failed.png"; String url="https://cdn.example/"+source;
  context.getBean(ArticleMediaWriter.class).pending(List.of(source),List.of(new ArticleUploadFileBO("图片.png","IMAGE","image/png",100)),9);
  when(cos.resolveManagedObjectKey(url)).thenReturn(source);
  when(context.getBean(ManagedArticleMediaInspector.class).inspect(anyString(),eq("IMAGE"),eq("image/png"),eq(100L)))
   .thenReturn(new ManagedArticleMediaInspector.Metadata("image/png",100,2,2,null));
  jdbc.execute("CREATE TRIGGER article_test_fail_media BEFORE INSERT ON media_attachment FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic media insert failure'");
  try { assertThrows(RuntimeException.class,()->media.register("IMAGE","图片",url,9)); }
  finally { jdbc.execute("DROP TRIGGER article_test_fail_media"); }
  assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM media_attachment WHERE object_key LIKE 'article/retained/%'",Integer.class));
  assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM media_upload_record WHERE target_type=8 AND status=0 AND bound_attachment_id IS NULL",Integer.class));
 }
 @Test void categoryLimitDailyDedupAndCursorIdentityBindingAreEnforced() {
  long c=category(true);
  int existingCategories=jdbc.queryForObject("SELECT COUNT(*) FROM article_category",Integer.class);
  for(int n=existingCategories;n<100;n++) jdbc.update("INSERT INTO article_category(name,display_order,enabled,created_at,updated_at,created_by,updated_by) VALUES(?,0,1,NOW(6),NOW(6),9,9)","synthetic-"+n);
  assertEquals("/problems/category-limit-reached",assertThrows(ContractProblemException.class,()->articles.createCategory(new ArticleCategorySaveBO("第101项",0,true))).getType());
  var a=articles.create(command(c,null,"第一篇",null)); a=articles.publish(a.id(),etag(a));
  var b=articles.create(command(c,null,"第二篇",null)); b=articles.publish(b.id(),etag(b));
  var page=articles.listPublic(c,null,1,"-publishedAt"); assertNotNull(page.nextCursor());
  assertEquals(1,articles.listPublic(c,page.nextCursor(),1,"-publishedAt").items().size());
  UserContext.setUserId(43L); assertEquals("/problems/invalid-cursor",assertThrows(ContractProblemException.class,()->articles.listPublic(c,page.nextCursor(),1,"-publishedAt")).getType());
  jdbc.update("INSERT INTO view_log(user_id,target_type,target_id,view_day) VALUES(43,8,?,?)",a.id(),java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).minusDays(1));
  interactionViews.view(TargetType.ARTICLE,a.id()); interactionViews.view(TargetType.ARTICLE,a.id()); assertEquals(2,articles.statistics(a.id()).viewCount());
 }
 @Test void oldAnonymousLikeCountAliasDoesNotCreateAnonymousArticleAccess() {
  var article=published(); var root=comments.create(article.id(),new ArticleCommentCreateBO("历史评论",null,null));
  UserContext.clear();
  assertThrows(BusinessException.class,()->interactionCounts.getLikeCount(TargetType.ARTICLE,article.id()));
  assertThrows(BusinessException.class,()->interactionCounts.getLikeCount(TargetType.COMMENT,root.id()));
 }
 void record(List<Map<String,Object>> fixtures,String schema,org.springframework.mock.web.MockHttpServletResponse response) throws Exception {
  assertEquals("no-store",response.getHeader("Cache-Control"));
  fixtures.add(Map.of("schema",schema,"status",response.getStatus(),"body",context.getBean(ObjectMapper.class).readTree(response.getContentAsByteArray())));
 }
 long verifiedImage() {
  jdbc.update("INSERT INTO media_attachment(type,kind,registered,registered_by,object_key,url,original_name,sort_order,content_type,size_bytes,width,height,verified) VALUES(1,'IMAGE',1,9,'article/retained/test/image','https://cdn.example/image','图片',0,'image/png',100,20,20,1)");
  return jdbc.queryForObject("SELECT id FROM media_attachment WHERE object_key='article/retained/test/image'",Long.class);
 }
 @Configuration @EnableTransactionManagement
 @Import({cn.jualn.miniapp.module.admin.operation.AdminAuditTestConfiguration.class,ArticleServiceImpl.class,ArticleWriter.class,ArticleBodyCodec.class,ArticleMediaService.class,ArticleMediaWriter.class,
  MediaUploadRecordService.class,CommentTargetAccess.class,CanonicalCommentService.class,ArticleCommentWriter.class,ArticleCommentNotifications.class,
  cn.jualn.miniapp.module.interact.service.impl.InteractionStatisticsServiceImpl.class,cn.jualn.miniapp.module.comment.service.impl.CommentStatisticsServiceImpl.class})
 static class Config {
  @Bean ObjectMapper json() { return new ObjectMapper(); }
  @Bean DataSource source() { String url=System.getProperty("event.test.jdbcUrl"); if(url==null || !url.matches("jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/article_contract_test")) throw new IllegalArgumentException("Dedicated disposable Article DB required"); return new DriverManagerDataSource(url+"?sslMode=DISABLED&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai","root",""); }
  @Bean DataSourceTransactionManager transactions(DataSource source) { return new DataSourceTransactionManager(source); }
  @Bean SqlSessionFactory sessions(DataSource source) throws Exception {
   var factory=new MybatisSqlSessionFactoryBean(); factory.setDataSource(source); var config=new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true); factory.setConfiguration(config);
   var global=new com.baomidou.mybatisplus.core.config.GlobalConfig(); global.setMetaObjectHandler(new cn.jualn.miniapp.config.MybatisPlusConfig().metaObjectHandler()); factory.setGlobalConfig(global);
   factory.setMapperLocations(new ClassPathResource("mapper/ArticleMapper.xml"),new ClassPathResource("mapper/ArticleCategoryMapper.xml"),new ClassPathResource("mapper/ArticlePublisherMapper.xml"),new ClassPathResource("mapper/CommentMapper.xml"),new ClassPathResource("mapper/LikeRecordMapper.xml"),new ClassPathResource("mapper/MediaAttachmentMapper.xml"),new ClassPathResource("mapper/MediaUploadRecordMapper.xml"),new ClassPathResource("mapper/InteractionStatisticsMapper.xml")); return factory.getObject();
  }
  <T> MapperFactoryBean<T> mapper(Class<T> type,SqlSessionFactory sessions) { var bean=new MapperFactoryBean<>(type); bean.setSqlSessionFactory(sessions); return bean; }
  @Bean MapperFactoryBean<ArticleMapper> articles(SqlSessionFactory s) { return mapper(ArticleMapper.class,s); }
  @Bean MapperFactoryBean<ArticleCategoryMapper> categories(SqlSessionFactory s) { return mapper(ArticleCategoryMapper.class,s); }
  @Bean MapperFactoryBean<ArticlePublisherMapper> publishers(SqlSessionFactory s) { return mapper(ArticlePublisherMapper.class,s); }
  @Bean MapperFactoryBean<CommentMapper> comments(SqlSessionFactory s) { return mapper(CommentMapper.class,s); }
  @Bean MapperFactoryBean<LikeRecordMapper> likes(SqlSessionFactory s) { return mapper(LikeRecordMapper.class,s); }
  @Bean MapperFactoryBean<InteractionStatisticsMapper> statistics(SqlSessionFactory s) { return mapper(InteractionStatisticsMapper.class,s); }
  @Bean MapperFactoryBean<ShareRecordMapper> shares(SqlSessionFactory s) { return mapper(ShareRecordMapper.class,s); }
  @Bean MapperFactoryBean<ViewLogMapper> views(SqlSessionFactory s) { return mapper(ViewLogMapper.class,s); }
  @Bean MapperFactoryBean<MediaAttachmentMapper> attachments(SqlSessionFactory s) { return mapper(MediaAttachmentMapper.class,s); }
  @Bean MapperFactoryBean<MediaUploadRecordMapper> uploads(SqlSessionFactory s) { return mapper(MediaUploadRecordMapper.class,s); }
  @Bean MapperFactoryBean<cn.jualn.miniapp.module.learningtask.mapper.LearningTaskMapper> taskMapper(SqlSessionFactory s) {return mapper(cn.jualn.miniapp.module.learningtask.mapper.LearningTaskMapper.class,s);}
  @Bean cn.jualn.miniapp.module.learningtask.service.LearningTaskArticleUsage taskUsage(cn.jualn.miniapp.module.learningtask.mapper.LearningTaskMapper tasks) {return new cn.jualn.miniapp.module.learningtask.service.LearningTaskArticleUsage(tasks);}
  @Bean ArticleAuthorization auth() { var auth=mock(ArticleAuthorization.class); when(auth.require(any())).thenReturn(9L); return auth; }
  @Bean ArticleOrganizationPolicy organizations(ArticleAuthorization auth,ArticleMapper mapper) {
   var policy=mock(ArticleOrganizationPolicy.class);
   when(policy.beginDeletion()).thenReturn(9L);
   when(policy.mediaOrganizations()).thenReturn(List.of("synthetic-article-org"));
   when(policy.begin(anyString())).thenAnswer(i->{String permission=i.getArgument(0);return auth.require(switch(permission){case "article:read"->ArticleAuthorization.Capability.READ;case "article:publish"->ArticleAuthorization.Capability.PUBLISH;case "article:unpublish"->ArticleAuthorization.Capability.UNPUBLISH;default->ArticleAuthorization.Capability.EDIT;});});
   when(policy.readable(anyLong())).thenReturn(List.of("synthetic-article-org"));
   when(policy.organizationRepresentation(any())).thenAnswer(i->((cn.jualn.miniapp.module.article.entity.Article)i.getArgument(0)).getPublisherOrgId()!=null);
   when(policy.prepare(anyString(),any(),any(),anyBoolean())).thenAnswer(i->{Long id=i.getArgument(1);ArticleSaveBO command=i.getArgument(2);if(command!=null && Long.valueOf(0).equals(command.publisherId())) throw ArticleOrganizationPolicy.migration();var prior=id==null?null:mapper.selectById(id);boolean freeze=(Boolean)i.getArgument(3) && (prior==null || !"PUBLISHED".equals(prior.getPublishStatus()));return new ArticleOrganizationPolicy.Prepared(9,"synthetic-article-org","组织署名",freeze,List.of());});
   when(policy.check(anyLong(),anyString(),any(),any(),anyBoolean())).thenAnswer(i->{if((Boolean)i.getArgument(4)) auth.require(ArticleAuthorization.Capability.PUBLISH);cn.jualn.miniapp.module.article.entity.Article prior=i.getArgument(2);boolean freeze=(Boolean)i.getArgument(4) && (prior==null || !"PUBLISHED".equals(prior.getPublishStatus()));return new ArticleOrganizationPolicy.Prepared(9,"synthetic-article-org","组织署名",freeze,List.of());});
   doAnswer(i->{var after=(cn.jualn.miniapp.module.article.entity.Article)i.getArgument(1);var proof=(ArticleOrganizationPolicy.Prepared)i.getArgument(3);mapper.organizationAudit(UUID.randomUUID().toString(),after,null,null,proof.actor(),i.getArgument(2),"[{\"fixture\":\"ArticleDatabaseTest\"}]",java.time.LocalDateTime.now());return null;}).when(policy).audit(any(),any(),anyString(),any(),any());
   return policy;
  }
  @Bean ArticleSafetyChecks safety() { return mock(ArticleSafetyChecks.class); }
  @Bean cn.jualn.miniapp.module.audit.service.VideoSafetyChecks videoSafety() { return mock(cn.jualn.miniapp.module.audit.service.VideoSafetyChecks.class); }
  @Bean ManagedArticleMediaInspector inspector() { return mock(ManagedArticleMediaInspector.class); }
  @Bean RedisService redis() { return mock(RedisService.class); }
  @Bean SubjectExistenceService targets() { return mock(SubjectExistenceService.class); }
  @Bean NotifyService notifyService() { return mock(NotifyService.class); }
  @Bean cn.jualn.miniapp.module.user.service.UserAccountService userAccounts() {return mock(cn.jualn.miniapp.module.user.service.UserAccountService.class);}
  @Bean UserService users() { var users=mock(UserService.class); when(users.getSimpleInfo(anyLong())).thenAnswer(i->UserSimpleBO.builder().id(i.getArgument(0)).nickname("有效昵称").avatarUrl("https://example.org/avatar.png").build()); return users; }
  @Bean CosService cos() { var cos=mock(CosService.class); when(cos.buildPublicUrl(anyString())).thenAnswer(i->"https://cdn.example/"+i.getArgument(0)); return cos; }
  @Bean cn.jualn.miniapp.common.mapper.EnumConverter enums() { return new cn.jualn.miniapp.common.mapper.EnumConverter(); }
  @Bean MediaConverter mediaConverter() { return Mappers.getMapper(MediaConverter.class); }
  @Bean MediaService media(MediaConverter c,MediaAttachmentMapper a,SubjectExistenceService t,CosService cos,MediaUploadRecordService u,ArticleMediaService articleMedia) { return new MediaServiceImpl(org.mockito.Mockito.mock(cn.jualn.miniapp.module.authorization.service.CurrentAuthorization.class), c,a,t,cos,u,articleMedia); }
  @Bean AttachmentReadService reads(cn.jualn.miniapp.module.media.service.MediaQueries m,cn.jualn.miniapp.module.media.service.MediaAccess access,ArticleService a) { return new AttachmentReadServiceImpl(m,access,org.mockito.Mockito.mock(cn.jualn.miniapp.module.activity.service.ActivityPublicAccess.class),org.mockito.Mockito.mock(cn.jualn.miniapp.module.publicevent.service.PublicEventPublicAccess.class),org.mockito.Mockito.mock(cn.jualn.miniapp.module.officialcontent.service.OfficialContentService.class),a,org.mockito.Mockito.mock(cn.jualn.miniapp.module.post.service.PostPublicAccess.class)); }
  @Bean cn.jualn.miniapp.module.comment.mapper.CommunityCommentMapper communityComments() {return mock(cn.jualn.miniapp.module.comment.mapper.CommunityCommentMapper.class);}
  @Bean CommentNotificationFactsService commentFacts(CommentMapper c) { return id->{var v=c.articleCommentIncludingDeleted(id); return v==null?null:new CommentNotificationFactsService.Fact(id,v.getUserId(),v.getTargetType(),v.getTargetId(),v.getContent());}; }
  @Bean cn.jualn.miniapp.module.post.service.PostPublicAccess postAccess() {return mock(cn.jualn.miniapp.module.post.service.PostPublicAccess.class);}
  @Bean cn.jualn.miniapp.module.activity.service.ActivityPublicAccess activityAccess() {return mock(cn.jualn.miniapp.module.activity.service.ActivityPublicAccess.class);}
  @Bean cn.jualn.miniapp.module.publicevent.service.PublicEventPublicAccess eventAccess() {return mock(cn.jualn.miniapp.module.publicevent.service.PublicEventPublicAccess.class);}
  @Bean InteractionLikes interactionLikes(InteractionTestServices bundle) { return bundle.likes(); }
  @Bean InteractionLikeQueries interactionLikeQueries(InteractionTestServices bundle) { return bundle.likeQueries(); }
  @Bean InteractionCounts interactionCounts(InteractionTestServices bundle) { return bundle.counts(); }
  @Bean InteractionShares interactionShares(InteractionTestServices bundle) { return bundle.shares(); }
  @Bean InteractionViews interactionViews(InteractionTestServices bundle) { return bundle.views(); }
  @Bean InteractionTestServices interactionBundle(LikeRecordMapper l,ShareRecordMapper s,RedisService r,SubjectExistenceService t,CommentNotificationFactsService f,UserService u,NotifyService n,ArticleService a,CommentTargetAccess c,ViewLogMapper v,ArticleCommentNotifications notifications) {
   return new InteractionTestServices(l, s, r, t, new cn.jualn.miniapp.module.interact.service.InteractionSubjects(java.util.List.of(new cn.jualn.miniapp.module.post.service.PostInteractionSubject(mock(PostMapper.class),r),new cn.jualn.miniapp.module.activity.service.ActivityInteractionSubject(mock(ActivityMapper.class),r),new cn.jualn.miniapp.module.publicevent.service.PublicEventInteractionSubject(mock(PublicEventMapper.class)))), org.mockito.Mockito.mock(cn.jualn.miniapp.module.post.service.PostNotificationFactsService.class), f, u, org.mockito.Mockito.mock(cn.jualn.miniapp.module.user.service.UserAccountService.class), n, a, c, v, notifications);
  }
  @Bean cn.jualn.miniapp.module.media.service.MediaQueries mediaQueries(MediaConverter c,MediaAttachmentMapper a,
      org.springframework.beans.factory.ObjectProvider<cn.jualn.miniapp.module.media.service.ArticleMediaService> article) {
    return new cn.jualn.miniapp.module.media.service.MediaQueriesService(c,a,
      org.mockito.Mockito.mock(cn.jualn.miniapp.module.subject.service.SubjectExistenceService.class),
      article.getIfAvailable(()->org.mockito.Mockito.mock(cn.jualn.miniapp.module.media.service.ArticleMediaService.class)));
  }
  @Bean cn.jualn.miniapp.module.media.service.MediaAccess mediaAccess(MediaAttachmentMapper a,
      cn.jualn.miniapp.module.media.service.MediaQueries q,MediaUploadRecordService uploads,
      org.springframework.beans.factory.ObjectProvider<cn.jualn.miniapp.module.authorization.service.CurrentAuthorization> auth,
      org.springframework.beans.factory.ObjectProvider<cn.jualn.miniapp.module.media.service.ArticleMediaService> article) {
    return new cn.jualn.miniapp.module.media.service.MediaAccessService(auth.getIfAvailable(()->org.mockito.Mockito.mock(cn.jualn.miniapp.module.authorization.service.CurrentAuthorization.class)),a,q,uploads,
      article.getIfAvailable(()->org.mockito.Mockito.mock(cn.jualn.miniapp.module.media.service.ArticleMediaService.class)));
  }

 }
}
