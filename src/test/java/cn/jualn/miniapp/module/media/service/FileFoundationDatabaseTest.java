package cn.jualn.miniapp.module.media.service;

import cn.jualn.miniapp.module.authorization.service.CurrentAuthorization;
import cn.jualn.miniapp.module.authorization.model.RbacModels;

import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.exception.*;
import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.module.media.bo.*;
import cn.jualn.miniapp.module.media.entity.*;
import cn.jualn.miniapp.module.media.mapper.*;
import cn.jualn.miniapp.module.media.converter.MediaConverter;
import cn.jualn.miniapp.module.media.service.impl.MediaServiceImpl;
import cn.jualn.miniapp.third.cos.client.CosClient;
import cn.jualn.miniapp.third.cos.service.CosService;
import cn.jualn.miniapp.third.cos.config.CosProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.baomidou.mybatisplus.core.*;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.mapstruct.factory.Mappers;
import javax.sql.DataSource;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real V73 -> V74 upgrade, MyBatis and Spring transactions; COS and current identity are explicit doubles. */
@EnabledIfSystemProperty(named="event.test.jdbcUrl",matches="jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/file_foundation_test(_[a-z0-9]+)?")
class FileFoundationDatabaseTest {
 static final String KEY="activity/upload/9/source.pdf", URL="https://cdn.example/"+KEY;
 AnnotationConfigApplicationContext context; JdbcTemplate jdbc; AttachmentRegistrationService registration;
 MediaService media; cn.jualn.miniapp.module.media.service.MediaQueries queries; cn.jualn.miniapp.module.media.service.MediaAccess access; MediaUploadRecordService lifecycle; MediaUploadRecordMapper uploads;
 AttachmentManagementService management; CosClient cos; TransactionTemplate tx;
 @BeforeAll static void migrate() throws Exception {
  String url=System.getProperty("event.test.jdbcUrl");
  String base=url.substring(0,url.lastIndexOf('/'))+"/?sslMode=DISABLED&allowPublicKeyRetrieval=true";
  try(var connection=java.sql.DriverManager.getConnection(base,"root","");var sql=connection.createStatement()) {
   sql.execute("CREATE DATABASE "+url.substring(url.lastIndexOf('/')+1)+" CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
  }
  var old=org.flywaydb.core.Flyway.configure().dataSource(url+"?sslMode=DISABLED&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai","root","").target("73").load();old.migrate();
  try(var connection=java.sql.DriverManager.getConnection(url+"?sslMode=DISABLED&allowPublicKeyRetrieval=true","root","");var sql=connection.createStatement()) {
   sql.execute("INSERT INTO media_attachment(type,kind,registered,registered_by,url,original_name) VALUES(3,'PDF',1,9,'https://legacy.example/file','historical')");
  }
  var current=org.flywaydb.core.Flyway.configure().dataSource(url+"?sslMode=DISABLED&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai","root","").load();current.migrate();current.validate();
  try(var connection=java.sql.DriverManager.getConnection(url+"?sslMode=DISABLED&allowPublicKeyRetrieval=true","root","");var sql=connection.createStatement();var rows=sql.executeQuery("SELECT retention,expires_at FROM media_attachment WHERE original_name='historical'")) {
   assertTrue(rows.next());assertEquals("FORMAL",rows.getString(1));assertNull(rows.getObject(2));
  }
 }
 @BeforeEach void open() {
  context=new AnnotationConfigApplicationContext();context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("controlled-media-test",Map.of("app.media.public-base-url","https://api.example")));context.register(Config.class);context.refresh();jdbc=new JdbcTemplate(context.getBean(DataSource.class));
  jdbc.update("DELETE FROM event_attachment_link");jdbc.update("DELETE FROM media_attachment_registration");
  jdbc.update("DELETE FROM media_upload_record");jdbc.update("DELETE FROM media_attachment");
  registration=context.getBean(AttachmentRegistrationService.class);media=context.getBean(MediaService.class);queries=context.getBean(cn.jualn.miniapp.module.media.service.MediaQueries.class);access=context.getBean(cn.jualn.miniapp.module.media.service.MediaAccess.class);
  lifecycle=context.getBean(MediaUploadRecordService.class);uploads=context.getBean(MediaUploadRecordMapper.class);
  management=context.getBean(AttachmentManagementService.class);cos=context.getBean(CosClient.class);
  tx=new TransactionTemplate(context.getBean(DataSourceTransactionManager.class));
  tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
  cn.jualn.miniapp.security.AdminContextFixture.open(9);UserContext.setUserId(9L);
  lifecycle.recordPending(9L,TargetType.ACTIVITY,List.of(KEY),now().plusHours(1));
 }
 @AfterEach void close() {UserContext.clear();cn.jualn.miniapp.security.AdminContextFixture.close();if(context!=null)context.close();}
 static LocalDateTime now() {return LocalDateTime.now(ZoneId.of("Asia/Shanghai"));}
 MediaAttachmentBO register(String key) {return registration.register(9,key,"PDF","guide",URL);}
 void link(long id,long target) {media.replaceAttachmentLinks(TargetType.ACTIVITY,target,List.of(new AttachmentLinkBO(id,0)));}
 int count(String table) {return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class);}
 @Test void registrationReplaysCurrentStateAndNeverRenewsExpiry() {
  var a=register("intent");assertEquals("CANDIDATE",a.getRetention());assertNotNull(a.getExpiresAt());
  assertTrue(a.getObjectKey().startsWith("activity/retained/9/"));
  var replay=register("intent");assertEquals(a.getId(),replay.getId());assertEquals(a.getExpiresAt(),replay.getExpiresAt());
  assertEquals(a.getId(),register("same-object-other-key").getId());assertEquals(1,count("media_attachment"));
  assertEquals("/problems/idempotency-key-reused",assertThrows(ContractProblemException.class,()->registration.register(9,"intent","PDF","changed",URL)).getType());
  assertEquals("/problems/attachment-registration-conflict",assertThrows(ContractProblemException.class,()->registration.register(9,"other","PDF","changed",URL)).getType());
  link(a.getId(),101);var formal=register("intent");assertEquals("FORMAL",formal.getRetention());assertNull(formal.getExpiresAt());
  assertEquals(1,management.list(9,1,20).totalItems());assertEquals(0,management.list(10,1,20).totalItems());
 }
 @Test void pendingDependenciesAllowSameIntentRetryAndFingerprintRemainsFixed() {
  doThrow(ContractProblemException.conflict("attachment-not-ready","not uploaded")).when(cos).copyObject(eq(KEY),anyString());
  assertEquals("/problems/attachment-not-ready",assertThrows(ContractProblemException.class,()->register("retry")).getType());
  assertEquals("/problems/idempotency-key-reused",assertThrows(ContractProblemException.class,()->registration.register(9,"retry","PDF","different",URL)).getType());
  doNothing().when(cos).copyObject(anyString(),anyString());assertNotNull(register("retry").getId());
 }
 @Test void concurrentSameObjectAndSameIntentCommitOneResource() throws Exception {
  ExecutorService pool=Executors.newFixedThreadPool(4);
  try {
   var tasks=new ArrayList<Callable<Long>>();for(int i=0;i<8;i++) {String key=i%2==0?"same":"key-"+i;tasks.add(()->register(key).getId());}
   Set<Long> ids=new HashSet<>();for(var result:pool.invokeAll(tasks))ids.add(result.get(10,TimeUnit.SECONDS));
   assertEquals(1,ids.size());assertEquals(1,count("media_attachment"));
  } finally {pool.shutdownNow();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS));}
 }
 @Test void rollbackDoesNotAdoptOrReplaceOldLinks() {
  var first=register("first");link(first.getId(),101);
  var second=registration.register(9,"second","LINK","external","https://outside.example/new");
  assertThrows(IllegalStateException.class,()->tx.executeWithoutResult(s->{link(second.getId(),101);throw new IllegalStateException("save failed");}));
  assertEquals(first.getId(),queries.listLinkedAttachments(TargetType.ACTIVITY,101L).get(0).getId());
  assertEquals("CANDIDATE",registration.register(9,"second","LINK","external","https://outside.example/new").getRetention());
  assertEquals(second.getExpiresAt(),registration.register(9,"second","LINK","external","https://outside.example/new").getExpiresAt());
  assertEquals(1,management.list(9,1,20).totalItems());
 }
 @Test void existingReferencesCanBeMaintainedByAnotherManagerButCannotBeReusedElsewhere() {
  long id=register("owner").getId();link(id,101);cn.jualn.miniapp.security.AdminContextFixture.open(10);
  assertDoesNotThrow(()->link(id,101));assertThrows(BusinessException.class,()->link(id,102));
  assertThrows(ContractProblemException.class,()->management.delete(10,id));
  media.replaceAttachmentLinks(TargetType.ACTIVITY,101L,List.of());assertEquals("FORMAL",queries.getAttachment(id).getRetention());
 }
 @Test void expiryAndCleanupNeverResurrectOnReplay() {
  var value=register("expire");jdbc.update("UPDATE media_attachment SET expires_at=DATE_SUB(NOW(),INTERVAL 1 HOUR) WHERE id=?",value.getId());
  jdbc.update("UPDATE media_upload_record SET cleanup_after=DATE_SUB(NOW(),INTERVAL 1 HOUR)");
  assertEquals("/problems/attachment-candidate-expired",assertThrows(ContractProblemException.class,()->link(value.getId(),101)).getType());
  assertEquals(2,lifecycle.cleanupExpiredBatch());assertEquals(0,count("event_attachment_link"));
  assertEquals(410,assertThrows(ContractProblemException.class,()->register("expire")).getStatus().value());
  assertThrows(BusinessException.class,()->queries.getAttachment(value.getId()));
 }
 @Test void formalDeletionHonorsReferencesHistoryAndReplayTombstone() {
  var value=register("delete");assertEquals("/problems/attachment-not-formal",assertThrows(ContractProblemException.class,()->management.delete(9,value.getId())).getType());
  link(value.getId(),101);assertEquals("/problems/attachment-in-use",assertThrows(ContractProblemException.class,()->management.delete(9,value.getId())).getType());
  media.replaceAttachmentLinks(TargetType.ACTIVITY,101L,List.of());
  var history=context.getBean(cn.jualn.miniapp.module.audit.service.AttachmentHistoryReader.class);when(history.retained(value.getId(),value.getUrl())).thenReturn(true);
  assertEquals("/problems/attachment-retained",assertThrows(ContractProblemException.class,()->management.delete(9,value.getId())).getType());
  when(history.retained(value.getId(),value.getUrl())).thenReturn(false);management.delete(9,value.getId());
  assertEquals(0,management.list(9,1,20).totalItems());assertThrows(BusinessException.class,()->queries.getAttachment(value.getId()));
  assertThrows(BusinessException.class,()->link(value.getId(),102));
  assertEquals("/problems/attachment-deleted",assertThrows(ContractProblemException.class,()->register("delete")).getType());
 }
 @Test void ordinaryBindingsRequireInspectedServerSnapshotAndRollbackAtomically() throws Exception {
  String source="post/upload/9/photo.png";lifecycle.recordPending(9L,TargetType.POST,List.of(source),now().plusHours(1));
  assertThrows(BusinessException.class,()->tx.executeWithoutResult(s->lifecycle.bindPending(9L,TargetType.POST,1L,List.of(source))));
  doAnswer(i->{var image=new java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_RGB);
   javax.imageio.ImageIO.write(image,"png",((java.nio.file.Path)i.getArgument(1)).toFile());return null;}).when(cos).downloadObject(anyString(),any(),anyLong());
  String snapshot=media.prepareOwnedImage(TargetType.POST,source);assertTrue(snapshot.startsWith("post/retained/"));
  assertThrows(IllegalStateException.class,()->tx.executeWithoutResult(s->{lifecycle.bindPending(9L,TargetType.POST,1L,List.of(snapshot));throw new IllegalStateException();}));
  assertEquals(0,uploads.selectForUpdate(snapshot).getStatus());
  tx.executeWithoutResult(s->lifecycle.bindPending(9L,TargetType.POST,1L,List.of(snapshot)));
  assertEquals(1,uploads.selectForUpdate(snapshot).getStatus());
 }
 @Test void privateCommunityImageKeepsStorageCapabilityOutOfThePublishedDeclaration() {
  String source="post/upload/9/photo.png";lifecycle.recordPending(9L,TargetType.POST,List.of(source),now().plusHours(1));
  doAnswer(i->{var image=new java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_RGB);javax.imageio.ImageIO.write(image,"png",((java.nio.file.Path)i.getArgument(1)).toFile());return null;}).when(cos).downloadObject(anyString(),any(),anyLong());
  var prepared=media.prepareNewAttachments(TargetType.POST,List.of(AttachmentItemBO.builder().type(cn.jualn.miniapp.module.media.enums.MediaType.IMAGE).objectKey(source).build()));
  assertTrue(prepared.get(0).getObjectKey().startsWith("post/retained/"));assertTrue(prepared.get(0).getUrl().matches("https://api[.]example/v1/media/files/[0-9]+/content"));assertFalse(prepared.get(0).getUrl().contains("signature"));
  tx.executeWithoutResult(s->media.replaceAttachments(MediaAttachmentSaveBO.builder().targetType(TargetType.POST).targetId(1L).attachments(prepared).build()));
  assertEquals(1,uploads.selectForUpdate(prepared.get(0).getObjectKey()).getStatus());assertEquals(prepared.get(0).getUrl(),queries.listAttachments(TargetType.POST,1L).get(0).getUrl());
 }
 @Test void saveClaimWinsAgainstCleanupAfterDeadlineWithoutDanglingReference() throws Exception {
  var value=register("save-wins");var deadline=now().plusSeconds(2);
  jdbc.update("UPDATE media_attachment SET expires_at=? WHERE id=?",deadline,value.getId());
  jdbc.update("UPDATE media_upload_record SET cleanup_after=? WHERE object_key=?",deadline,value.getObjectKey());
  var claimed=new CountDownLatch(1);var release=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
  try {
   var save=pool.submit(()->{cn.jualn.miniapp.security.AdminContextFixture.open(9);try {tx.executeWithoutResult(s->{link(value.getId(),101);claimed.countDown();try {assertTrue(release.await(10,TimeUnit.SECONDS));}catch(InterruptedException e){throw new IllegalStateException(e);}});}finally {cn.jualn.miniapp.security.AdminContextFixture.close();}});
   assertTrue(claimed.await(10,TimeUnit.SECONDS));Thread.sleep(2200);
   var cleanup=pool.submit(()->lifecycle.cleanupExpiredBatch());release.countDown();save.get(10,TimeUnit.SECONDS);
   assertEquals(0,cleanup.get(10,TimeUnit.SECONDS));assertEquals("FORMAL",queries.getAttachment(value.getId()).getRetention());
   verify(cos,never()).deleteObject(value.getObjectKey());assertEquals(1,count("event_attachment_link"));
  } finally {release.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS));}
 }
 @Test void cleanupClaimWinsAndSaveCannotReferenceDeletedBytes() throws Exception {
  var value=register("cleanup-wins");jdbc.update("UPDATE media_attachment SET expires_at=DATE_SUB(NOW(),INTERVAL 1 SECOND) WHERE id=?",value.getId());
  jdbc.update("UPDATE media_upload_record SET cleanup_after=DATE_SUB(NOW(),INTERVAL 1 SECOND) WHERE object_key=?",value.getObjectKey());
  var deleting=new CountDownLatch(1);var release=new CountDownLatch(1);var pool=Executors.newSingleThreadExecutor();
  doAnswer(i->{deleting.countDown();assertTrue(release.await(10,TimeUnit.SECONDS));return null;}).when(cos).deleteObject(value.getObjectKey());
  try {
   var cleanup=pool.submit(()->lifecycle.cleanupExpiredBatch());assertTrue(deleting.await(10,TimeUnit.SECONDS));
   assertEquals("/problems/attachment-candidate-expired",assertThrows(ContractProblemException.class,()->link(value.getId(),101)).getType());
   release.countDown();assertEquals(1,cleanup.get(10,TimeUnit.SECONDS));assertEquals(0,count("event_attachment_link"));
  } finally {release.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS));}
 }
 @Test void deletionAndNewReferenceSerializeOnResourceLock() throws Exception {
  var value=register("delete-race");link(value.getId(),101);media.replaceAttachmentLinks(TargetType.ACTIVITY,101L,List.of());
  var ready=new CountDownLatch(1);var release=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
  try {
   var deletion=pool.submit(()->tx.executeWithoutResult(s->{management.delete(9,value.getId());ready.countDown();try {assertTrue(release.await(10,TimeUnit.SECONDS));}catch(InterruptedException e){throw new IllegalStateException(e);}}));
   assertTrue(ready.await(10,TimeUnit.SECONDS));var save=pool.submit(()->{cn.jualn.miniapp.security.AdminContextFixture.open(9);try {assertThrows(BusinessException.class,()->link(value.getId(),102));}finally {cn.jualn.miniapp.security.AdminContextFixture.close();}});
   release.countDown();deletion.get(10,TimeUnit.SECONDS);save.get(10,TimeUnit.SECONDS);assertEquals(0,count("event_attachment_link"));
  } finally {release.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS));}
 }
 @Test void canonicalHttpEnforcesKeyAndReturnsCurrentRetentionWithoutCaching() throws Exception {
  var json=new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
   .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
   .addMixIn(org.springframework.http.ProblemDetail.class,org.springframework.http.converter.json.ProblemDetailJacksonMixin.class);
  var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
   new cn.jualn.miniapp.module.media.controller.CanonicalAttachmentController(mock(AttachmentReadService.class),access,registration,
    new cn.jualn.miniapp.module.media.converter.AttachmentConverter(),management))
   .setControllerAdvice(new cn.jualn.miniapp.common.exception.GlobalExceptionHandler())
   .setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(json)).build();
  String body="{\"kind\":\"PDF\",\"name\":\"guide\",\"url\":\""+URL+"\"}";
  var absent=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/v1/admin/attachments").contentType("application/json").content(body)).andReturn().getResponse();
  assertEquals(400,absent.getStatus());assertEquals(0,count("media_attachment"));
  var created=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/v1/admin/attachments").header("Idempotency-Key","http").contentType("application/json").content(body)).andReturn().getResponse();
  assertEquals(201,created.getStatus());assertEquals("no-store",created.getHeader("Cache-Control"));
  var candidate=json.readTree(created.getContentAsString());assertEquals("CANDIDATE",candidate.path("retention").asText());assertTrue(candidate.hasNonNull("expiresAt"));
  long id=Long.parseLong(candidate.path("attachmentId").asText());link(id,101);
  var formal=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/v1/admin/attachments").header("Idempotency-Key","http").contentType("application/json").content(body)).andReturn().getResponse();
  var current=json.readTree(formal.getContentAsString());assertEquals("FORMAL",current.path("retention").asText());assertFalse(current.has("expiresAt"));
 }
 @Test void lostOrdinaryCopyResponseIsTrackedAndCannotBind() {
  String source="post/upload/9/uncertain.png";lifecycle.recordPending(9L,TargetType.POST,List.of(source),now().plusHours(1));
  doThrow(new ExternalServiceException(cn.jualn.miniapp.common.result.ResultCode.EXTERNAL_SERVICE_ERROR,"cos","unknown copy")).when(cos).copyObject(eq(source),anyString());
  assertEquals(503,assertThrows(ContractProblemException.class,()->media.prepareOwnedImage(TargetType.POST,source)).getStatus().value());
  assertEquals(3,count("media_upload_record"));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM media_upload_record WHERE status=1",Integer.class));
 }
 @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"IMAGE","VIDEO"})
 void articleRegistrationKeepsPlaybackAndAdoptsAllMediaAtomically(String kind) {
  String type="IMAGE".equals(kind)?"image/png":"video/mp4";String source="article/upload/9/source-"+kind;
  context.getBean(ArticleMediaWriter.class).pending(List.of(source),List.of(new ArticleUploadFileBO("source",kind,type,24)),9);
  var value=registration.register(9,"article-"+kind,kind,"media","https://cdn.example/"+source);
  assertEquals("CANDIDATE",value.getRetention());assertTrue(value.getObjectKey().startsWith("article/retained/9/"));
  if("VIDEO".equals(kind)) assertEquals(3.0,value.getDurationSeconds());
  assertEquals(value.getId(),registration.register(9,"article-other-"+kind,kind,"media","https://cdn.example/"+source).getId());
  assertThrows(IllegalStateException.class,()->tx.executeWithoutResult(s->{media.replaceAttachmentLinks(TargetType.ARTICLE,201L,List.of(new AttachmentLinkBO(value.getId(),0)));throw new IllegalStateException();}));
  assertEquals("CANDIDATE",queries.getAttachment(value.getId()).getRetention());
  media.replaceAttachmentLinks(TargetType.ARTICLE,201L,List.of(new AttachmentLinkBO(value.getId(),0)));
  assertEquals("FORMAL",queries.getAttachment(value.getId()).getRetention());
  assertEquals(value.getId(),queries.requireArticleAttachments(Map.of(value.getId(),kind)).get(0).getId());
 }
 @Test void opaqueRegistrationKeysKeepCaseAndTrailingCharactersDistinct() {
  var first=registration.register(9,"Opaque","LINK","first","https://outside.example/first");
  var second=registration.register(9,"opaque","LINK","second","https://outside.example/second");
  var third=registration.register(9,"opaque ","LINK","third","https://outside.example/third");
  assertEquals(3,new HashSet<>(List.of(first.getId(),second.getId(),third.getId())).size());
  assertEquals(3,count("media_attachment_registration"));
 }
 @Test void readablePurposeAndOwnershipAreFilteredBeforePagingAndCounting() {
  var first=register("scope-activity");media.replaceAttachmentLinks(TargetType.EXAM,101L,List.of(new AttachmentLinkBO(first.getId(),0)));
  String key="exam/upload/9/another.pdf";lifecycle.recordPending(9L,TargetType.EXAM,List.of(key),now().plusHours(1));
  var second=registration.register(9,"scope-event","PDF","other","https://cdn.example/"+key);
  assertTrue(second.getObjectKey().startsWith("exam/retained/9/"));
  media.replaceAttachmentLinks(TargetType.EXAM,102L,List.of(new AttachmentLinkBO(second.getId(),0)));
  var auth=context.getBean(CurrentAuthorization.class);
  when(auth.current(9)).thenReturn(new RbacModels.Decision(9,RbacModels.Source.ASSIGNMENT,"v","c",now(),true,List.of(),
   List.of(new RbacModels.Grant("source","test","ACTIVITY","GLOBAL",List.of("media:read"),null))));
  assertDoesNotThrow(()->access.authorizeReusableRead(first.getId(),9));
  var page=management.list(9,1,1);assertEquals(1,page.totalItems());assertEquals(List.of(first.getId()),page.items().stream().map(MediaAttachmentBO::getId).toList());
 }
 @Configuration(proxyBeanMethods=false) @EnableTransactionManagement(proxyTargetClass=true)
 @cn.jualn.miniapp.module.post.OrdinaryGrowthTestConfiguration.Fixture
    static class Config {
  @Bean MediaDeliveryUrls deliveryUrls(MediaUploadRecordMapper mapper){var urls=new MediaDeliveryUrls(mapper);org.springframework.test.util.ReflectionTestUtils.setField(urls,"baseUrl","https://api.example");return urls;}

  @Bean DataSource dataSource() {return new DriverManagerDataSource(System.getProperty("event.test.jdbcUrl")+"?sslMode=DISABLED&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai","root","");}
  @Bean DataSourceTransactionManager transactions(DataSource data) {return new DataSourceTransactionManager(data);}
  @Bean cn.jualn.miniapp.common.mapper.EnumConverter enums() {return new cn.jualn.miniapp.common.mapper.EnumConverter();}
  @Bean MediaConverter converter() {return Mappers.getMapper(MediaConverter.class);}
  @Bean SqlSessionFactory sessions(DataSource data) throws Exception {
   var factory=new MybatisSqlSessionFactoryBean();factory.setDataSource(data);
   var config=new MybatisConfiguration();config.setMapUnderscoreToCamelCase(true);factory.setConfiguration(config);
   var global=new GlobalConfig();global.setMetaObjectHandler(new cn.jualn.miniapp.config.MybatisPlusConfig().metaObjectHandler());factory.setGlobalConfig(global);
   var plugin=new com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor();plugin.addInnerInterceptor(new com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor(com.baomidou.mybatisplus.annotation.DbType.MYSQL));factory.setPlugins(plugin);
   factory.setMapperLocations(new ClassPathResource("mapper/MediaUploadRecordMapper.xml"),new ClassPathResource("mapper/MediaAttachmentMapper.xml"));return factory.getObject();
  }
  static <T> MapperFactoryBean<T> mapper(Class<T> type,SqlSessionFactory sessions) {var factory=new MapperFactoryBean<>(type);factory.setSqlSessionFactory(sessions);return factory;}
  @Bean MapperFactoryBean<MediaAttachmentMapper> attachments(SqlSessionFactory s) {return mapper(MediaAttachmentMapper.class,s);}
  @Bean MapperFactoryBean<MediaUploadRecordMapper> uploadsMapper(SqlSessionFactory s) {return mapper(MediaUploadRecordMapper.class,s);}
  @Bean MapperFactoryBean<AttachmentRegistrationMapper> intents(SqlSessionFactory s) {return mapper(AttachmentRegistrationMapper.class,s);}
  @Bean ObjectMapper json() {return new ObjectMapper();}
  @Bean CosClient cos() {var cos=mock(CosClient.class);when(cos.buildPublicUrl(anyString())).thenAnswer(i->"https://cdn.example/"+i.getArgument(0));return cos;}
  @Bean CosService cosService(CosClient cos,ObjectMapper json) {return new CosService(cos,new CosProperties("unused","unused","campus-123","ap-test","https://cdn.example",600,"cdn.example"),json);}
  @Bean CurrentAuthorization authorization() {var auth=mock(CurrentAuthorization.class);when(auth.current(anyLong())).thenAnswer(i->new RbacModels.Decision(i.getArgument(0),RbacModels.Source.LEGACY,"v","c",now(),true,List.of("*"),List.of()));return auth;}
  @Bean MediaUploadRecordService lifecycle(MediaUploadRecordMapper uploads,CosService cos) {return new MediaUploadRecordService(uploads,cos);}
  @Bean cn.jualn.miniapp.module.article.service.ArticleAuthorization articleAuthorization() {
   var auth=mock(cn.jualn.miniapp.module.article.service.ArticleAuthorization.class);
   when(auth.require(any())).thenReturn(9L);return auth;
  }
  @Bean ManagedArticleMediaInspector articleInspector() {
   var inspector=mock(ManagedArticleMediaInspector.class);
   when(inspector.inspect(anyString(),eq("IMAGE"),anyString(),anyLong())).thenReturn(new ManagedArticleMediaInspector.Metadata("image/png",24,2,2,null));
   when(inspector.inspect(anyString(),eq("VIDEO"),anyString(),anyLong())).thenReturn(new ManagedArticleMediaInspector.Metadata("video/mp4",24,2,2,3.0));return inspector;
  }
  @Bean ArticleMediaWriter articleWriter(MediaUploadRecordMapper u,MediaAttachmentMapper a,MediaUploadRecordService l,CosService cos,cn.jualn.miniapp.module.article.service.ArticleAuthorization auth) {
   return new ArticleMediaWriter(u,a,l,cos,auth,mock(cn.jualn.miniapp.module.audit.service.VideoSafetyChecks.class));
  }
  @Bean ArticleMediaService article(MediaAttachmentMapper a,MediaUploadRecordMapper u,MediaConverter c,CosService cos,MediaUploadRecordService l,ArticleMediaWriter w,ManagedArticleMediaInspector i,cn.jualn.miniapp.module.article.service.ArticleAuthorization auth) {
   return new ArticleMediaService(a,u,c,cos,l,w,i,auth);
  }
  @Bean ManagedAttachmentInspector inspector() {var inspector=mock(ManagedAttachmentInspector.class);when(inspector.inspect(anyString(),anyString())).thenReturn(new ManagedAttachmentInspector.Metadata("application/pdf",10));return inspector;}
  @Bean MediaService media(CurrentAuthorization auth,MediaConverter c,MediaAttachmentMapper a,CosService cos,MediaUploadRecordService l,ArticleMediaService article) {return new MediaServiceImpl(auth,c,a,mock(cn.jualn.miniapp.module.subject.service.SubjectExistenceService.class),cos,l,article);}
  @Bean AttachmentRegistrationService registration(AttachmentRegistrationMapper i,MediaAttachmentMapper a,MediaUploadRecordMapper u,MediaUploadRecordService l,ArticleMediaService article,ManagedAttachmentInspector inspector,CurrentAuthorization auth,CosService cos,MediaConverter c,ObjectMapper json,DataSourceTransactionManager tx) {return new AttachmentRegistrationService(i,a,u,l,article,inspector,auth,cos,c,json,tx);}
  @Bean cn.jualn.miniapp.module.audit.service.AttachmentHistoryReader history() {return mock(cn.jualn.miniapp.module.audit.service.AttachmentHistoryReader.class);}
  @Bean MapperFactoryBean<cn.jualn.miniapp.module.content.mapper.AttachmentReferenceMapper> referencesMapper(SqlSessionFactory s) {return mapper(cn.jualn.miniapp.module.content.mapper.AttachmentReferenceMapper.class,s);}
  @Bean cn.jualn.miniapp.module.content.service.AttachmentReferenceReader references(cn.jualn.miniapp.module.content.mapper.AttachmentReferenceMapper m) {return new cn.jualn.miniapp.module.content.service.AttachmentReferenceReader(m);}
  @Bean AttachmentManagementService management(CurrentAuthorization auth,MediaAttachmentMapper a,MediaUploadRecordMapper u,MediaConverter c,cn.jualn.miniapp.module.content.service.AttachmentReferenceReader r,cn.jualn.miniapp.module.audit.service.AttachmentHistoryReader h) {return new AttachmentManagementService(auth,a,u,c,r,h);}
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
