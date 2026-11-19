package cn.jualn.miniapp.module.article;

import cn.jualn.miniapp.module.article.bo.*;
import cn.jualn.miniapp.module.article.service.*;
import cn.jualn.miniapp.module.article.service.impl.ArticleReferenceReadServiceImpl;
import cn.jualn.miniapp.module.article.mapper.*;
import cn.jualn.miniapp.module.article.converter.ArticleConverter;
import cn.jualn.miniapp.module.article.dto.ArticleSaveRequest;
import cn.jualn.miniapp.module.article.controller.ArticleFavoriteController;
import cn.jualn.miniapp.module.media.service.*;
import cn.jualn.miniapp.module.media.bo.*;
import cn.jualn.miniapp.common.exception.*;
import cn.jualn.miniapp.common.web.StrongEtag;
import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.stp.StpUtil;
import cn.jualn.miniapp.common.security.AdminStpUtil;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Inherits the existing Article regression; new facts use real MySQL/transactions/MVC.
 * Identity, safety and COS are explicit doubles. No provider or device acceptance is claimed. */
class ArticleContentDatabaseTest extends ArticleDatabaseTest {
 @BeforeAll static void migrateOwnedBusinessFixture() {
  String expected=System.getProperty("learning.business.test.serverUuid");if(expected==null)return;
  String url=System.getProperty("event.test.jdbcUrl");if(url==null || !url.matches("jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/article_contract_test")) throw new IllegalArgumentException("Exclusive Article fixture required");
  var ds=new org.springframework.jdbc.datasource.DriverManagerDataSource(url+"?sslMode=DISABLED&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai","root","");
  assertEquals(expected,new org.springframework.jdbc.core.JdbcTemplate(ds).queryForObject("SELECT @@server_uuid",String.class));
  var flyway=org.flywaydb.core.Flyway.configure().dataSource(ds).load();flyway.migrate();flyway.validate();
  var fixture=new org.springframework.jdbc.core.JdbcTemplate(ds);
  fixture.update("INSERT INTO user_profile(openid) SELECT 'm01-migration-legacy' WHERE NOT EXISTS(SELECT 1 FROM user_profile WHERE openid='m01-migration-legacy')");
  fixture.update("INSERT INTO post(user_id,title,content,status) SELECT id,'Historical post','Preserved',1 FROM user_profile WHERE openid='m01-migration-legacy' AND NOT EXISTS(SELECT 1 FROM post WHERE user_id=(SELECT id FROM user_profile WHERE openid='m01-migration-legacy'))");
 }
 @Test void publishedTaskProtectsNormalUnpublishAndSafetyRetainsReasonAndTaskHistory() throws Exception {
  var live=published();long task=syntheticReference(live.id(),"PUBLISHED");
  try {
   var usage=articles.learningTaskUsage(live.id());assertTrue(usage.hasReferences());assertTrue(usage.hasActiveReferences());assertFalse(usage.canUnpublish());
   record(wireEvidence,"ArticleLearningTaskUsage",mvc.perform(get("/v1/admin/articles/"+live.id()+"/learning-task-usage")).andExpect(status().isOk()).andExpect(jsonPath("$.hasActiveReferences").value(true)).andReturn().getResponse());
   assertEquals("/problems/article-in-use",assertThrows(ContractProblemException.class,()->articles.unpublish(live.id(),etag(live))).getType());
   assertThrows(ContractProblemException.class,()->articles.unpublish(live.id(),etag(live),"SAFETY",null));
   mvc.perform(post("/v1/admin/articles/"+live.id()+":unpublish").header("If-Match",etag(live)).contentType("application/json").content("{\"mode\":\"SAFETY\"}")).andExpect(status().isBadRequest());
   mvc.perform(post("/v1/admin/articles/"+live.id()+":unpublish").header("If-Match",etag(live)).contentType("application/json").content("null")).andExpect(status().isBadRequest());
   var response=mvc.perform(post("/v1/admin/articles/"+live.id()+":unpublish").header("If-Match",etag(live)).contentType("application/json").content("{\"mode\":\"SAFETY\",\"reason\":\"发现风险内容，立即停展\"}")).andExpect(status().isOk()).andReturn().getResponse();
   record(wireEvidence,"ArticleAdminDetail",response);var hidden=articles.getAdmin(live.id());assertEquals("UNPUBLISHED",hidden.publishStatus());
   assertEquals("PUBLISHED",jdbc.queryForObject("SELECT status FROM learning_task WHERE id=?",String.class,task));
   assertEquals("发现风险内容，立即停展",jdbc.queryForObject("SELECT reason FROM article_safety_unpublish WHERE article_id=?",String.class,live.id()));
   jdbc.update("UPDATE learning_task SET status='CLOSED',closed_at=NOW(6) WHERE id=?",task);
   assertEquals("/problems/article-in-use",assertThrows(ContractProblemException.class,()->articles.delete(hidden.id(),etag(hidden))).getType());
  } finally {jdbc.update("DELETE FROM learning_task WHERE id=?",task);}
 }
 @Test void failedSafetyAuditRollsBackUnpublishAndDoesNotRequireTaskInspection() {
  var live=published();long task=syntheticReference(live.id(),"PUBLISHED");
  try {
   jdbc.execute("CREATE TRIGGER learning_safety_audit_failure BEFORE INSERT ON article_safety_unpublish FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic audit unavailable'");
   assertThrows(RuntimeException.class,()->articles.unpublish(live.id(),etag(live),"SAFETY","风险停展"));
   assertEquals("PUBLISHED",jdbc.queryForObject("SELECT publish_status FROM article WHERE id=?",String.class,live.id()));
   assertEquals(live.revision(),jdbc.queryForObject("SELECT revision FROM article WHERE id=?",Long.class,live.id()));
  } finally {jdbc.execute("DROP TRIGGER IF EXISTS learning_safety_audit_failure");jdbc.update("DELETE FROM learning_task WHERE id=?",task);}
 }
 @Test void safetyUnpublishDoesNotWaitForAReferencedTaskRowLock() throws Exception {
  var live=published();long task=syntheticReference(live.id(),"PUBLISHED");var locked=new CountDownLatch(1);var release=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
  var tx=new org.springframework.transaction.support.TransactionTemplate(context.getBean(org.springframework.transaction.PlatformTransactionManager.class));
  var holder=pool.submit(()->tx.execute(status->{jdbc.queryForObject("SELECT id FROM learning_task WHERE id=? FOR UPDATE",Long.class,task);locked.countDown();try {assertTrue(release.await(10,TimeUnit.SECONDS));}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}return null;}));
  try {
   assertTrue(locked.await(10,TimeUnit.SECONDS));
   var safety=pool.submit(()->{initializeIdentity();try{return articles.unpublish(live.id(),etag(live),"SAFETY","风险内容立即停展");}finally{SaManager.getSaTokenContext().clearContext();}});
   assertEquals("UNPUBLISHED",safety.get(5,TimeUnit.SECONDS).publishStatus());
   release.countDown();holder.get(10,TimeUnit.SECONDS);assertEquals("PUBLISHED",jdbc.queryForObject("SELECT status FROM learning_task WHERE id=?",String.class,task));
  } finally {release.countDown();holder.get(10,TimeUnit.SECONDS);pool.shutdownNow();jdbc.update("DELETE FROM learning_task WHERE id=?",task);}
 }
 @Test void taskPublicationAndNormalUnpublishCannotBothCommit() throws Exception {
  var live=published();long task=syntheticReference(live.id(),"DRAFT");
  var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
  var tx=new org.springframework.transaction.support.TransactionTemplate(context.getBean(org.springframework.transaction.PlatformTransactionManager.class));
  tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
  try {
   var publication=pool.submit(()->{start.await();try {tx.executeWithoutResult(status->{
    jdbc.queryForObject("SELECT id FROM learning_task WHERE id=? FOR UPDATE",Long.class,task);
    context.getBean(ArticleReferenceReadService.class).requirePublicReference(live.id());
    jdbc.update("UPDATE learning_task SET status='PUBLISHED',published_at=NOW(6) WHERE id=?",task);
   });return true;}catch(ContractProblemException | org.springframework.dao.PessimisticLockingFailureException expected){return false;}});
   var unpublish=pool.submit(()->{initializeIdentity();start.await();try {articles.unpublish(live.id(),etag(live));return true;}
    catch(ContractProblemException | org.springframework.dao.PessimisticLockingFailureException expected){return false;}finally{SaManager.getSaTokenContext().clearContext();}});
   start.countDown();boolean p=publication.get(15,TimeUnit.SECONDS),u=unpublish.get(15,TimeUnit.SECONDS);
   assertTrue(p || u);assertFalse(p && u);
   if("PUBLISHED".equals(jdbc.queryForObject("SELECT status FROM learning_task WHERE id=?",String.class,task)))
    assertEquals("PUBLISHED",jdbc.queryForObject("SELECT publish_status FROM article WHERE id=?",String.class,live.id()));
  } finally {pool.shutdownNow();jdbc.update("DELETE FROM learning_task WHERE id=?",task);}
 }
 long syntheticReference(long article,String status) {
  long id=com.baomidou.mybatisplus.core.toolkit.IdWorker.getId();
  jdbc.update("INSERT INTO learning_task(id,title,description,article_id,requirements,review_required,first_deadline,status,revision,deadline_revision,created_by,created_at,updated_at,published_at) VALUES(?,'private other organization task','',?,'{}',0,DATE_SUB(NOW(6),INTERVAL 1 DAY),?,1,1,9,NOW(6),NOW(6),IF(?='PUBLISHED',NOW(6),NULL))",id,article,status,status);return id;
 }
 static final List<Map<String,Object>> wireEvidence=new ArrayList<>();
 @AfterAll static void writeWireEvidence() throws Exception {new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(java.nio.file.Path.of("target/article-content-http-responses.json").toFile(),wireEvidence);}

 @Override Class<?> configuration() {return ContentConfig.class;}
 @BeforeEach void identities() {
  var storage=mock(cn.dev33.satoken.context.model.SaStorage.class);Map<String,Object> values=new HashMap<>();
  when(storage.get(anyString())).thenAnswer(i->values.get(i.getArgument(0)));when(storage.set(anyString(),any())).thenAnswer(i->{values.put(i.getArgument(0),i.getArgument(1));return storage;});when(storage.delete(anyString())).thenAnswer(i->{values.remove(i.getArgument(0));return storage;});
  SaManager.getSaTokenContext().setContext(mock(cn.dev33.satoken.context.model.SaRequest.class),mock(cn.dev33.satoken.context.model.SaResponse.class),storage);
  AdminStpUtil.STP_LOGIC.switchTo(9L);StpUtil.switchTo(42L);
  jdbc.update("DELETE FROM article_favorite");
  jdbc.update("INSERT INTO user_profile(id,openid,status) VALUES(42,'article-favorite42',1),(43,'article-favorite43',1) ON DUPLICATE KEY UPDATE status=1,ban_expire_at=NULL");
 }
 @AfterEach void clearIdentity() {SaManager.getSaTokenContext().clearContext();}
 @Test void scenesSearchAndEffectiveMappingBindPaginationWithoutFallback() {
  var root=articles.categories(true).stream()
      .filter(category -> category.sceneCodes().equals(List.of("PARTY_NEWS"))).findFirst().orElseThrow();
  var child=articles.createCategory(new ArticleCategorySaveBO("Child",0,true,root.id()));
  var outside=articles.createCategory(new ArticleCategorySaveBO("Outside",0,true));
  for(long c:new long[]{root.id(),child.id(),outside.id()}) {var d=articles.create(command(c,null,"needle",null));articles.publish(d.id(),etag(d));}
  for(String scene:ArticleContentFields.SCENES) {
   assertEquals("PARTY_NEWS".equals(scene) ? 2 : 0,
       articles.listPublic(null,null,null,20,"-publishedAt","needle",scene).items().size());
  }
  assertThrows(ContractProblemException.class,()->articles.createCategory(
      new ArticleCategorySaveBO("Duplicate scene",0,true,null,List.of("PARTY_NEWS"))));
  assertThrows(ContractProblemException.class,()->articles.replaceCategory(root.id(),
      new ArticleCategorySaveBO(root.name(),root.displayOrder(),true,null,List.of()),
      StrongEtag.of("article-category",root.id(),root.revision())));
  assertEquals(1,articles.listPublic(child.id(),false,null,20,"-publishedAt","needle","PARTY_NEWS").items().size());
  assertTrue(articles.listPublic(outside.id(),false,null,20,"-publishedAt","needle","PARTY_NEWS").items().isEmpty());
  assertTrue(articles.listPublic(null,null,null,20,"-publishedAt","absent","PARTY_NEWS").items().isEmpty());
  var page=articles.listPublic(null,null,null,1,"-publishedAt","needle","PARTY_NEWS");
  assertEquals("/problems/invalid-cursor",assertThrows(ContractProblemException.class,()->articles.listPublic(null,null,page.nextCursor(),1,"-publishedAt","other","PARTY_NEWS")).getType());
  articles.replaceCategory(root.id(),new ArticleCategorySaveBO(root.name(),root.displayOrder(),false),StrongEtag.of("article-category",root.id(),root.revision()));
  assertTrue(articles.listPublic(null,null,null,20,"-publishedAt",null,"PARTY_NEWS").items().isEmpty());
  assertThrows(ContractProblemException.class,()->articles.listPublic(null,null,page.nextCursor(),1,"-publishedAt","needle","PARTY_NEWS"));
  assertEquals(3,articles.listPublic(null,null,20,"-publishedAt").items().size());
  assertThrows(ContractProblemException.class,()->articles.replaceCategory(child.id(),new ArticleCategorySaveBO("Child",0,true,null,List.of("E_SAFETY")),StrongEtag.of("article-category",child.id(),child.revision())));
 }
 @Test void independentFilesAndSourceSurviveOldReplacementAndClearAtomically() throws Exception {
  long c=category(true);long file=document("a.pdf");var base=command(c,null,"body",null);var json=context.getBean(ObjectMapper.class);
  var source=json.createObjectNode().put("name","Source").put("url","https://example.org/source");
  var saved=articles.create(new ArticleSaveBO(base.title(),base.summary(),c,1L,null,base.body(),true,null,List.of(file),source,true));
  assertEquals(List.of(file),articles.getAdmin(saved.id()).attachmentIds());assertEquals(source,articles.getAdmin(saved.id()).source());
  var retained=articles.replace(saved.id(),base,etag(saved));assertEquals(List.of(file),retained.attachmentIds());assertEquals(source,retained.source());
  when(context.getBean(ManagedArticleMediaInspector.class).documentText(anyString(),anyString(),anyLong())).thenReturn("safe extracted document");
  var live=articles.publish(saved.id(),etag(retained));
  mvc.perform(get("/v1/articles/"+live.id())).andExpect(status().isOk()).andExpect(jsonPath("$.attachments[0].kind").value("PDF")).andExpect(jsonPath("$.media.length()").value(0)).andExpect(jsonPath("$.source.name").value("Source"));
  verify(safety).text(eq(9L),contains("safe extracted document"));
  var cleared=articles.replace(live.id(),new ArticleSaveBO(base.title(),base.summary(),c,1L,null,base.body(),true,null,List.of(),null,true),etag(live));
  assertTrue(cleared.attachmentIds().isEmpty());assertNull(cleared.source());assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM event_attachment_link WHERE target_type=8 AND target_id=?",Integer.class,live.id()));
  assertEquals("FORMAL",jdbc.queryForObject("SELECT retention FROM media_attachment WHERE id=?",String.class,file));
 }
 long document(String name) {
  String key="article-resource/test/"+UUID.randomUUID();
  jdbc.update("INSERT INTO media_attachment(type,kind,registered,registered_by,object_key,url,original_name,sort_order,content_type,size_bytes,verified,retention) VALUES(2,'PDF',1,9,?,?,?,0,'application/pdf',100,1,'FORMAL')",key,"https://cdn.example/"+key,name);
  return jdbc.queryForObject("SELECT id FROM media_attachment WHERE object_key=?",Long.class,key);
 }
 @Test void hiddenFavoriteHasOnlyRelationshipAndDuplicatePutKeepsTime() throws Exception {
  var live=published();var favorites=context.getBean(ArticleFavorites.class);
  favorites.set(live.id());var at=jdbc.queryForObject("SELECT favorited_at FROM article_favorite WHERE user_id=42 AND article_id=?",java.time.LocalDateTime.class,live.id());favorites.set(live.id());
  assertEquals(at,jdbc.queryForObject("SELECT favorited_at FROM article_favorite WHERE user_id=42 AND article_id=?",java.time.LocalDateTime.class,live.id()));
  var hidden=articles.unpublish(live.id(),etag(live));assertTrue(favorites.get(live.id()).favorited());assertNull(favorites.list(null,20).items().get(0).article());
  StpUtil.switchTo(43L);assertTrue(favorites.list(null,20).items().isEmpty());assertThrows(ContractProblemException.class,()->favorites.get(live.id()));
  StpUtil.switchTo(42L);assertThrows(ContractProblemException.class,()->favorites.set(live.id()));
  var local=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new ArticleFavoriteController(favorites,new ArticleConverter())).setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(new ObjectMapper())).build();
  local.perform(get("/v1/users/me/article-favorites")).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].availability").value("UNAVAILABLE")).andExpect(jsonPath("$.items[0].article").doesNotExist());
  articles.delete(hidden.id(),etag(hidden));favorites.remove(live.id());favorites.remove(live.id());assertTrue(favorites.list(null,20).items().isEmpty());
 }
 @Test void deleteRestoreRetainsStateReferencesAndVersion() throws Exception {
  var live=published();assertThrows(ContractProblemException.class,()->articles.delete(live.id(),etag(live)));
  var hidden=articles.unpublish(live.id(),etag(live));long file=document("retained.pdf");
  var base=command(hidden.categoryId(),null,"retained",null);
  var saved=articles.replace(hidden.id(),new ArticleSaveBO(base.title(),base.summary(),base.categoryId(),1L,null,base.body(),true,null,List.of(file),null,true),etag(hidden));
  articles.delete(saved.id(),etag(saved));var deleted=articles.getAdmin(saved.id());assertNotNull(deleted.deletedAt());assertEquals("UNPUBLISHED",deleted.publishStatus());assertEquals(List.of(file),deleted.attachmentIds());
  articles.delete(saved.id(),etag(deleted));assertEquals(deleted.revision(),articles.getAdmin(saved.id()).revision());
  assertThrows(ContractProblemException.class,()->articles.replace(saved.id(),base,etag(deleted)));
  mvc.perform(get("/v1/admin/articles").param("deleted","true")).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].deletedBy.operatorId").value("9"));
  var restored=articles.restore(saved.id(),etag(deleted));assertNull(restored.deletedAt());assertEquals("UNPUBLISHED",restored.publishStatus());assertFalse(articles.isPubliclyVisible(saved.id()));assertEquals(live.publishedAt(),restored.publishedAt());
  assertThrows(ContractProblemException.class,()->articles.restore(saved.id(),etag(restored)));
 }
 @Test void allRetainedTaskStatesBlockDeletionAndUsageLeaksNoTaskData() {
  var live=published();var hidden=articles.unpublish(live.id(),etag(live));
  try {
   for(String state:List.of("DRAFT","PUBLISHED","CLOSED","CANCELLED")) {
    jdbc.update("INSERT INTO learning_task(title,description,article_id,requirements,review_required,first_deadline,status,created_by,created_at,updated_at,published_at,closed_at,cancelled_at,cancelled_by,cancellation_reason) VALUES('private task','',?,'{}',0,NOW(6)+INTERVAL 1 DAY,?,9,NOW(6),NOW(6),IF(?='DRAFT',NULL,NOW(6)),IF(?='CLOSED',NOW(6),NULL),IF(?='CANCELLED',NOW(6),NULL),IF(?='CANCELLED',9,NULL),IF(?='CANCELLED','reason',NULL))",hidden.id(),state,state,state,state,state,state);
    assertTrue(articles.learningTaskUsage(hidden.id()).hasReferences());assertFalse(articles.learningTaskUsage(hidden.id()).canDelete());
    assertEquals("/problems/article-in-use",assertThrows(ContractProblemException.class,()->articles.delete(hidden.id(),etag(hidden))).getType());
    jdbc.update("DELETE FROM learning_task WHERE article_id=?",hidden.id());
   }
  } finally {jdbc.update("DELETE FROM learning_task WHERE article_id=?",hidden.id());}
  articles.delete(hidden.id(),etag(hidden));
 }
 @Test void referenceAdmissionAndWithdrawalUseTheSameTransactionGuard() throws Exception {
  var live=published();var references=context.getBean(ArticleReferenceReadService.class);
  var tx=new org.springframework.transaction.support.TransactionTemplate(context.getBean(org.springframework.transaction.PlatformTransactionManager.class));
  var held=new CountDownLatch(1);var release=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
  try {
   var first=pool.submit(()->tx.execute(s->{references.requirePublicReference(live.id());held.countDown();try {assertTrue(release.await(5,TimeUnit.SECONDS));}catch(InterruptedException e) {throw new RuntimeException(e);}return null;}));
   assertTrue(held.await(5,TimeUnit.SECONDS));var second=pool.submit(()->articles.unpublish(live.id(),etag(live)));
   Thread.sleep(100);assertFalse(second.isDone());release.countDown();first.get(5,TimeUnit.SECONDS);var hidden=second.get(5,TimeUnit.SECONDS);
   assertThrows(ContractProblemException.class,()->tx.execute(s->{references.requirePublicReference(hidden.id());return null;}));
   assertTrue(references.publiclyVisibleIds(Set.of(hidden.id())).isEmpty());
  } finally {release.countDown();pool.shutdownNow();}
 }
 @Test void unknownPublicationEvidenceIsAnErrorInsteadOfEmptyOrUsableMaterial() {
  var live=published();jdbc.update("UPDATE article SET publisher_id=NULL WHERE id=?",live.id());jdbc.update("DELETE FROM article_organization_audit WHERE article_id=?",live.id());
  var references=context.getBean(ArticleReferenceReadService.class);
  assertEquals(503,assertThrows(ContractProblemException.class,()->references.requirePublicReference(live.id())).getStatus().value());
  assertEquals(503,assertThrows(ContractProblemException.class,()->references.publiclyVisibleIds(Set.of(live.id()))).getStatus().value());
  assertEquals(503,assertThrows(ContractProblemException.class,()->references.options(null,null,20,"synthetic-authorized-context")).getStatus().value());
 }
 @Test void bannedFavoriteOwnerCannotCreateOrReadRelationships() {
  var live=published();var favorites=context.getBean(ArticleFavorites.class);
  jdbc.update("UPDATE user_profile SET status=3,ban_expire_at=NULL WHERE id=42");
  assertThrows(BusinessException.class,()->favorites.set(live.id()));assertThrows(BusinessException.class,()->favorites.list(null,20));
  assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM article_favorite WHERE user_id=42",Integer.class));
 }
 @Test void actualTaskReferenceCommitSerializesWithWithdrawalAndBlocksDeletion() throws Exception {
  var live=published();var service=context.getBean(cn.jualn.miniapp.module.learningtask.service.LearningTaskService.class);
  var save=new cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Save("Racing task","Description",live.id(),new cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Requirements(true,0,0,0,List.of()),false,java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")).plusDays(1),null);
  var references=context.getBean(ArticleReferenceReadService.class);var providers=context.getBean(cn.jualn.miniapp.module.learningtask.service.LearningTaskProviders.class);
  var held=new CountDownLatch(1);var release=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);String key=UUID.randomUUID().toString();
  doAnswer(i->{references.requirePublicReference(i.getArgument(1));if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {held.countDown();assertTrue(release.await(10,TimeUnit.SECONDS));}return null;}).when(providers).article(anyLong(),anyLong());
  try {
   var creation=pool.submit(()->service.create(save,key));assertTrue(held.await(10,TimeUnit.SECONDS));
   var withdrawal=pool.submit(()->articles.unpublish(live.id(),etag(live)));Thread.sleep(100);assertFalse(withdrawal.isDone());
   release.countDown();var task=creation.get(10,TimeUnit.SECONDS);var hidden=withdrawal.get(10,TimeUnit.SECONDS);
   assertEquals(live.id(),task.task().articleId());assertEquals("/problems/article-in-use",assertThrows(ContractProblemException.class,()->articles.delete(hidden.id(),etag(hidden))).getType());
  } finally {release.countDown();pool.shutdownNow();pool.awaitTermination(10,TimeUnit.SECONDS);jdbc.update("DELETE FROM learning_task_request WHERE request_key=?",key);jdbc.update("DELETE FROM learning_task WHERE article_id=?",live.id());}
 }
 @Test void canonicalWirePreservesNewFieldsAndReturnsMinimalFavoritesAndLifecycle() throws Exception {
  long c=category(true),file=document("wire.pdf");var json=context.getBean(ObjectMapper.class);
  var body=json.createObjectNode().put("title","Wire").put("summary","").put("categoryId",Long.toString(c)).put("publisherId","1");body.set("body",command(c,null,"body",true).body());body.putArray("attachmentIds").add(Long.toString(file));body.putObject("source").put("name","Public source").put("url","https://example.org/source");
  var created=mvc.perform(post("/v1/admin/articles").contentType("application/json").content(body.toString())).andExpect(status().isCreated()).andExpect(jsonPath("$.attachmentIds[0]").value(Long.toString(file))).andReturn().getResponse();record(wireEvidence,"ArticleAdminDetail",created);
  String id=json.readTree(created.getContentAsByteArray()).path("id").asText();body.remove(List.of("attachmentIds","source"));
  var replaced=mvc.perform(put("/v1/admin/articles/"+id).header("If-Match",created.getHeader("ETag")).contentType("application/json").content(body.toString())).andExpect(status().isOk()).andExpect(jsonPath("$.source.name").value("Public source")).andExpect(jsonPath("$.attachmentIds[0]").value(Long.toString(file))).andReturn().getResponse();record(wireEvidence,"ArticleAdminDetail",replaced);
  when(context.getBean(ManagedArticleMediaInspector.class).documentText(anyString(),anyString(),anyLong())).thenReturn("Safe public material");
  var live=mvc.perform(post("/v1/admin/articles/"+id+":publish").header("If-Match",replaced.getHeader("ETag"))).andExpect(status().isOk()).andReturn().getResponse();record(wireEvidence,"ArticleAdminDetail",live);
  record(wireEvidence,"ArticleDetail",mvc.perform(get("/v1/articles/"+id)).andExpect(status().isOk()).andReturn().getResponse());
  record(wireEvidence,"ArticleLearningTaskUsage",mvc.perform(get("/v1/admin/articles/"+id+"/learning-task-usage")).andExpect(status().isOk()).andExpect(jsonPath("$.canDelete").value(false)).andReturn().getResponse());
  var favorites=context.getBean(ArticleFavorites.class);var local=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new ArticleFavoriteController(favorites,new ArticleConverter())).setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(json)).build();
  for(var request:List.of(put("/v1/articles/"+id+"/favorite"),get("/v1/articles/"+id+"/favorite"))) record(wireEvidence,"ArticleFavoriteState",local.perform(request).andExpect(status().isOk()).andExpect(jsonPath("$.favorited").value(true)).andReturn().getResponse());
  record(wireEvidence,"ArticleFavoritePage",local.perform(get("/v1/users/me/article-favorites")).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].availability").value("AVAILABLE")).andReturn().getResponse());
  var hidden=mvc.perform(post("/v1/admin/articles/"+id+":unpublish").header("If-Match",live.getHeader("ETag"))).andExpect(status().isOk()).andReturn().getResponse();
  mvc.perform(delete("/v1/admin/articles/"+id).header("If-Match",hidden.getHeader("ETag"))).andExpect(status().isNoContent());
  var deleted=mvc.perform(get("/v1/admin/articles/"+id)).andExpect(status().isOk()).andExpect(jsonPath("$.deletedAt").exists()).andReturn().getResponse();record(wireEvidence,"ArticleAdminDetail",deleted);
  record(wireEvidence,"ArticleAdminPage",mvc.perform(get("/v1/admin/articles").param("deleted","true")).andExpect(status().isOk()).andReturn().getResponse());
  record(wireEvidence,"ArticleFavoritePage",local.perform(get("/v1/users/me/article-favorites")).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].availability").value("UNAVAILABLE")).andExpect(jsonPath("$.items[0].article").doesNotExist()).andReturn().getResponse());
  record(wireEvidence,"ArticleAdminDetail",mvc.perform(post("/v1/admin/articles/"+id+":restore").header("If-Match",deleted.getHeader("ETag"))).andExpect(status().isOk()).andExpect(jsonPath("$.publishStatus").value("UNPUBLISHED")).andExpect(jsonPath("$.deletedAt").doesNotExist()).andReturn().getResponse());
  record(wireEvidence,"ArticleFavoriteState",local.perform(delete("/v1/articles/"+id+"/favorite")).andExpect(status().isOk()).andExpect(jsonPath("$.favorited").value(false)).andReturn().getResponse());
 }
 @Test void realTaskCreateAndUnchangedCorrectionSurviveMaterialWithdrawal() {
  var live=published();var service=context.getBean(cn.jualn.miniapp.module.learningtask.service.LearningTaskService.class);
  var save=new cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Save("Task","Description",live.id(),new cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Requirements(true,0,0,0,List.of()),false,java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")).plusDays(1),null);
  Long task=null;String requestKey=UUID.randomUUID().toString();
  try {
   var created=service.create(save,requestKey);task=created.task().taskId();assertEquals(live.id(),created.task().articleId());
   assertEquals(live.id(),jdbc.queryForObject("SELECT article_id FROM learning_task WHERE id=?",Long.class,task));
   var hidden=articles.unpublish(live.id(),etag(live));var current=service.get(task);
   assertEquals("UNAVAILABLE",current.task().articleAvailability());
   var edited=service.edit(task,new cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Edit("Corrected",null,false,null,null,null,null,null),cn.jualn.miniapp.module.learningtask.LearningTaskEtags.task(current.task()));
   assertEquals("Corrected",edited.task().title());assertEquals(live.id(),edited.task().articleId());
   assertEquals("/problems/article-in-use",assertThrows(ContractProblemException.class,()->articles.delete(hidden.id(),etag(hidden))).getType());
   assertThrows(ContractProblemException.class,()->service.create(save,UUID.randomUUID().toString()));
  } finally {if(task!=null) {jdbc.update("DELETE FROM learning_task_request WHERE operation='createLearningTask' AND target_id=0 AND request_key=?",requestKey);jdbc.update("DELETE FROM learning_task WHERE id=?",task);}}
 }
 @Test void documentCandidateAdoptionAndFailureKeepAllReferencesAtomic() {
  String source="article/9/"+UUID.randomUUID()+".pdf",url="https://cdn.example/"+source;
  var cos=context.getBean(cn.jualn.miniapp.third.cos.service.CosService.class);when(cos.resolveManagedObjectKey(url)).thenReturn(source);
  context.getBean(ArticleMediaWriter.class).pending(List.of(source),List.of(new ArticleUploadFileBO("material.pdf","PDF","application/pdf",100)),9);
  when(context.getBean(ManagedArticleMediaInspector.class).inspect(anyString(),eq("PDF"),eq("application/pdf"),eq(100L))).thenReturn(new ManagedArticleMediaInspector.Metadata("application/pdf",100,null,null,null));
  var file=context.getBean(ArticleMediaService.class).register("PDF","material.pdf",url,9);assertEquals("CANDIDATE",file.getRetention());
  var base=command(category(true),null,"Body",null);
  var command=new ArticleSaveBO(base.title(),base.summary(),base.categoryId(),1L,null,base.body(),true,null,List.of(file.getId()),null,false);
  jdbc.execute("CREATE TRIGGER article_test_fail_link BEFORE INSERT ON event_attachment_link FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic link failure'");
  try {assertThrows(RuntimeException.class,()->articles.create(command));} finally {jdbc.execute("DROP TRIGGER article_test_fail_link");}
  assertEquals("CANDIDATE",context.getBean(MediaQueries.class).getAttachment(file.getId()).getRetention());
  assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM article",Integer.class));
  var saved=articles.create(command);assertEquals("FORMAL",context.getBean(MediaQueries.class).getAttachment(file.getId()).getRetention());assertEquals(List.of(file.getId()),articles.getAdmin(saved.id()).attachmentIds());
  doThrow(ContractProblemException.conflict("publish-validation-failed","No safely extractable text")).when(context.getBean(ManagedArticleMediaInspector.class)).documentText(anyString(),anyString(),anyLong());
  assertThrows(ContractProblemException.class,()->articles.publish(saved.id(),etag(saved)));assertEquals(saved.revision(),articles.getAdmin(saved.id()).revision());assertEquals("DRAFT",articles.getAdmin(saved.id()).publishStatus());
 }
 @Configuration @Import({ArticleFavorites.class,ArticleReferenceReadServiceImpl.class,cn.jualn.miniapp.module.learningtask.service.impl.LearningTaskServiceImpl.class})
 static class ContentConfig extends ArticleDatabaseTest.Config {
  @Override @Bean SqlSessionFactory sessions(javax.sql.DataSource source) throws Exception {
   var factory=super.sessions(source);
   for(var resource:new org.springframework.core.io.support.PathMatchingResourcePatternResolver().getResources("classpath*:mapper/Learning*.xml")) try(var input=resource.getInputStream()) {new org.apache.ibatis.builder.xml.XMLMapperBuilder(input,factory.getConfiguration(),resource.toString(),factory.getConfiguration().getSqlFragments()).parse();}
   var userResource=new org.springframework.core.io.ClassPathResource("mapper/UserProfileMapper.xml");try(var input=userResource.getInputStream()) {new org.apache.ibatis.builder.xml.XMLMapperBuilder(input,factory.getConfiguration(),userResource.toString(),factory.getConfiguration().getSqlFragments()).parse();}
   return factory;
  }
  @Bean MapperFactoryBean<cn.jualn.miniapp.module.learningtask.mapper.LearningAssignmentMapper> assignmentMapper(SqlSessionFactory s){return mapper(cn.jualn.miniapp.module.learningtask.mapper.LearningAssignmentMapper.class,s);}
  @Bean MapperFactoryBean<cn.jualn.miniapp.module.learningtask.mapper.LearningSubmissionMapper> submissionMapper(SqlSessionFactory s){return mapper(cn.jualn.miniapp.module.learningtask.mapper.LearningSubmissionMapper.class,s);}
  @Bean MapperFactoryBean<cn.jualn.miniapp.module.learningtask.mapper.LearningPreviewMapper> previewMapper(SqlSessionFactory s){return mapper(cn.jualn.miniapp.module.learningtask.mapper.LearningPreviewMapper.class,s);}
  @Bean MapperFactoryBean<cn.jualn.miniapp.module.learningtask.mapper.LearningTaskLedgerMapper> ledgerMapper(SqlSessionFactory s){return mapper(cn.jualn.miniapp.module.learningtask.mapper.LearningTaskLedgerMapper.class,s);}
  @Bean(name="learningTaskClock") java.time.Clock clock(){return java.time.Clock.systemUTC();}
  @Bean cn.jualn.miniapp.module.learningtask.service.LearningTaskProviders taskProviders(ArticleReferenceReadService references) {
   var providers=mock(cn.jualn.miniapp.module.learningtask.service.LearningTaskProviders.class);
   when(providers.authorize(any(),nullable(Long.class),nullable(Long.class))).thenReturn(9L);
   when(providers.publiclyVisibleArticles(anySet())).thenAnswer(i->references.publiclyVisibleIds(i.getArgument(0)));
   doAnswer(i->{references.requirePublicReference(i.getArgument(1));return null;}).when(providers).article(anyLong(),anyLong());
   return providers;
  }
  @Bean MapperFactoryBean<ArticleFavoriteMapper> favoritesMapper(SqlSessionFactory s) {var bean=new MapperFactoryBean<ArticleFavoriteMapper>(ArticleFavoriteMapper.class);bean.setSqlSessionFactory(s);return bean;}
  @Bean MapperFactoryBean<cn.jualn.miniapp.module.user.mapper.UserProfileMapper> userMapper(SqlSessionFactory s){return mapper(cn.jualn.miniapp.module.user.mapper.UserProfileMapper.class,s);}
  @Bean cn.jualn.miniapp.module.user.service.UserCurrentLoginAccess currentUsers(cn.jualn.miniapp.module.user.mapper.UserProfileMapper users) {return new cn.jualn.miniapp.module.user.service.UserCurrentLoginAccess(users);}
 }
}
