package cn.jualn.miniapp.module.article;

import cn.jualn.miniapp.module.authorization.service.RbacAuthorizationReader;
import cn.jualn.miniapp.module.authorization.service.RbacCatalog;
import cn.jualn.miniapp.module.authorization.service.RbacIntegrationCatalog;
import cn.jualn.miniapp.module.authorization.mapper.RbacMapper;

import cn.dev33.satoken.SaManager;
import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.common.exception.GlobalExceptionHandler;
import cn.jualn.miniapp.common.security.AdminStpUtil;
import cn.jualn.miniapp.common.web.StrongEtag;
import cn.jualn.miniapp.module.admin.auth.support.AdminPermissionPolicy;
import cn.jualn.miniapp.module.article.bo.*;
import cn.jualn.miniapp.module.article.controller.admin.*;
import cn.jualn.miniapp.module.article.converter.ArticleConverter;
import cn.jualn.miniapp.module.article.service.*;
import cn.jualn.miniapp.module.media.service.ArticleMediaService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import javax.sql.DataSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Disposable MySQL, real RBAC reader, Article services, transactions and MVC; synthetic admin sessions and safety/COS doubles. */
@EnabledIfSystemProperty(named="event.test.jdbcUrl",matches="jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/article_contract_test")
class ArticleRbacDatabaseTest {
 AnnotationConfigApplicationContext context; JdbcTemplate jdbc; ArticleService articles; MockMvc mvc;
 @BeforeEach void setup() {
  context=new AnnotationConfigApplicationContext(Config.class); jdbc=new JdbcTemplate(context.getBean(DataSource.class)); articles=context.getBean(ArticleService.class);
  jdbc.update("DELETE FROM article_publisher_migration_evidence"); jdbc.update("DELETE FROM article_organization_audit"); jdbc.update("DELETE FROM article_publisher_organization_mapping");
  jdbc.update("DELETE FROM event_attachment_link WHERE target_type=8"); jdbc.update("DELETE FROM article"); jdbc.update("DELETE FROM article_category WHERE parent_id IS NOT NULL"); jdbc.update("DELETE FROM article_category WHERE scene_codes IS NULL OR JSON_LENGTH(scene_codes)=0"); jdbc.update("UPDATE article_category SET enabled=TRUE"); jdbc.update("DELETE FROM article_publisher");
  jdbc.update("DELETE FROM person_business_scope"); jdbc.update("DELETE FROM rbac_delegation_authority");
  jdbc.update("DELETE FROM rbac_authorization_change"); jdbc.update("DELETE FROM rbac_user_role_assignment"); jdbc.update("DELETE FROM rbac_authorization_authority");
  jdbc.update("UPDATE rbac_permission_definition SET lifecycle=IF(lifecycle='DISABLED','DISABLED','REGISTERED'),readiness='NOT_READY',grantable=0,unavailable_reason='PROVIDER_NOT_VERIFIED'");
  jdbc.update("UPDATE rbac_role SET lifecycle='REGISTERED',readiness='NOT_READY',grantable=0,unavailable_reason='PROVIDER_NOT_VERIFIED'");
  for(long id:List.of(801L,802L)) {
   jdbc.update("INSERT INTO user_profile(id,openid,role,status) VALUES(?,?,3,1) ON DUPLICATE KEY UPDATE status=1,deleted_at=NULL",id,"article-rbac-synthetic-"+id);
   jdbc.update("INSERT INTO rbac_authorization_authority VALUES(?,'ASSIGNMENT',?,UTC_TIMESTAMP(6))",id,UUID.randomUUID().toString());
  }
  for(String role:List.of("ROOT","CONTENT_EDITOR","CONTENT_PUBLISHER")) {
   jdbc.update("UPDATE rbac_permission_definition p JOIN rbac_role_permission m ON p.code=m.permission_code SET p.lifecycle='ENABLED',p.readiness='READY',p.grantable=1,p.unavailable_reason=NULL WHERE m.role_code=?",role);
   jdbc.update("UPDATE rbac_role SET lifecycle='ENABLED',readiness='READY',grantable=1,unavailable_reason=NULL WHERE code=?",role);
  }
  if(jdbc.queryForObject("SELECT COUNT(*) FROM person_organization WHERE id='synthetic-article-org'",Integer.class)==0) {
   String event=UUID.randomUUID().toString();jdbc.update("INSERT INTO person_foundation_event(id,aggregate_type,aggregate_id,aggregate_version,operation,after_json,operator_user_id,recorded_at,reason,verification_reference,source,receipt_id) VALUES(?,'ORGANIZATION','synthetic-article-org',1,'createPersonOrganization','{}',801,UTC_TIMESTAMP(6),'test','synthetic','LOCAL_MANUAL',?)",event,UUID.randomUUID().toString());
   jdbc.update("INSERT INTO person_organization VALUES('synthetic-article-org','组织署名','','ACTIVE',1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),?)",event);
  }
  jdbc.update("INSERT INTO article_publisher VALUES(1,'Synthetic official publisher',1,801,NOW(6))");
  jdbc.update("INSERT INTO article_category(name,display_order,enabled,revision,created_by,updated_by,created_at,updated_at) VALUES('Synthetic category',0,1,1,801,801,NOW(6),NOW(6))");
  var storage=mock(cn.dev33.satoken.context.model.SaStorage.class); Map<String,Object> values=new HashMap<>();
  when(storage.get(anyString())).thenAnswer(i->values.get(i.getArgument(0)));
  when(storage.set(anyString(),any())).thenAnswer(i->{values.put(i.getArgument(0),i.getArgument(1));return storage;});
  when(storage.delete(anyString())).thenAnswer(i->{values.remove(i.getArgument(0));return storage;});
  SaManager.getSaTokenContext().setContext(mock(cn.dev33.satoken.context.model.SaRequest.class),mock(cn.dev33.satoken.context.model.SaResponse.class),storage);
  AdminStpUtil.STP_LOGIC.switchTo(801L);
  mvc=MockMvcBuilders.standaloneSetup(new AdminArticleController(articles,new ArticleConverter()),new AdminArticleTaxonomyController(articles,new ArticleConverter()))
    .setControllerAdvice(new GlobalExceptionHandler()).build();
 }
 @AfterEach void close() { SaManager.getSaTokenContext().clearContext(); if(context!=null) context.close(); }
 @Test void editorCreatesDraftPublisherPublishesAndEditorNeedsPublishToEditLiveContent() throws Exception {
  assignment("CONTENT_EDITOR","ARTICLE"); var draft=articles.create(command("draft"));
  assertEquals("DRAFT",draft.publishStatus()); assertEquals(801L,jdbc.queryForObject("SELECT created_by FROM article WHERE id=?",Long.class,draft.id()));
  mvc.perform(get("/v1/admin/articles/"+draft.id())).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
  forbidden(()->articles.publish(draft.id(),etag(draft)));
  assignment("CONTENT_PUBLISHER","ARTICLE"); var live=articles.publish(draft.id(),etag(draft)); assertEquals("PUBLISHED",live.publishStatus());
  jdbc.update("UPDATE rbac_user_role_assignment SET revoked_at=UTC_TIMESTAMP(6),revoke_change_id=?,current_slot=NULL WHERE role_code='CONTENT_PUBLISHER'",UUID.randomUUID().toString());
  forbidden(()->articles.replace(live.id(),command("must not be saved"),etag(live)));
  forbidden(()->context.getBean(ArticleWriter.class).replace(live.id(),command("must not bypass service"),etag(live)));
  assertEquals("draft",articles.getAdmin(live.id()).title());
  assignment("CONTENT_PUBLISHER","ARTICLE"); var hidden=articles.unpublish(live.id(),etag(live)); assertEquals("UNPUBLISHED",hidden.publishStatus());
  assertFalse(articles.isPubliclyVisible(live.id()));
 }
 @Test void rootWrongNamespaceLegacyAndMissingAuthorityCannotUseArticle() throws Exception {
  assignment("ROOT","AUTHORIZATION"); mvc.perform(get("/v1/admin/articles")).andExpect(status().isOk());
  // Current ROOT owner capabilities permit read. Exact organization denial is tested with the real V2 policy.
  jdbc.update("UPDATE rbac_user_role_assignment SET revoked_at=UTC_TIMESTAMP(6),revoke_change_id=?,current_slot=NULL WHERE role_code='ROOT'",UUID.randomUUID().toString());
  assignment("CONTENT_EDITOR","OFFICIAL_CONTENT"); mvc.perform(get("/v1/admin/articles")).andExpect(status().isForbidden());
  jdbc.update("UPDATE rbac_authorization_authority SET source='LEGACY' WHERE user_id=801"); forbidden(()->articles.listAdmin(1,20,null,null,null,null,"-updatedAt"));
  jdbc.update("DELETE FROM rbac_authorization_authority WHERE user_id=801");
  assertEquals(503,assertThrows(ContractProblemException.class,()->articles.listAdmin(1,20,null,null,null,null,"-updatedAt")).getStatus().value());
 }
 @Test void publisherReadsMediaWithoutUploadAndCannotManageTaxonomy() {
  assignment("CONTENT_PUBLISHER","ARTICLE");
  var media=context.getBean(ArticleMediaService.class); assertDoesNotThrow(media::authorizeRead);
  forbidden(()->context.getBean(ArticleAuthorization.class).require(ArticleAuthorization.Capability.MEDIA));
  forbidden(()->articles.create(command("forbidden")));
  forbidden(()->articles.createCategory(new ArticleCategorySaveBO("forbidden",0,true)));
  forbidden(()->articles.replacePublisher(1,"forbidden",StrongEtag.of("article-publisher",1,1)));
  assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM article",Integer.class));
 }
 @Test void databaseExpiryRevocationAndMemberReadinessAreEffectiveImmediately() {
  String id=assignment("CONTENT_EDITOR","ARTICLE"); assertDoesNotThrow(()->articles.listAdmin(1,20,null,null,null,null,"-updatedAt"));
  jdbc.update("UPDATE rbac_user_role_assignment SET expires_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?",id);
  forbidden(()->articles.create(command("expired")));
  jdbc.update("UPDATE rbac_user_role_assignment SET expires_at=NULL WHERE id=?",id);
  jdbc.update("UPDATE rbac_permission_definition SET readiness='NOT_READY',grantable=0,unavailable_reason='PROVIDER_NOT_VERIFIED' WHERE code='official-content:edit'");
  forbidden(()->articles.create(command("incomplete role")));
  assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM article",Integer.class));
 }
 String assignment(String role,String namespace) {
  String id=UUID.randomUUID().toString(); jdbc.update("INSERT INTO rbac_user_role_assignment(id,user_id,role_code,namespace,scope_type,granted_at,version,grant_change_id) VALUES(?,801,?,?,'GLOBAL',UTC_TIMESTAMP(6)-INTERVAL 5 SECOND,?,?)",id,role,namespace,UUID.randomUUID().toString(),UUID.randomUUID().toString()); return id;
 }
 ArticleSaveBO command(String title) {
  var body=new ObjectMapper().createObjectNode().put("format","ARTICLE_RICH_TEXT").put("version",1);
  body.putArray("nodes").addObject().put("type","PARAGRAPH").putArray("runs").addObject().put("text","Synthetic article body");
  return new ArticleSaveBO(title,"",jdbc.queryForObject("SELECT id FROM article_category LIMIT 1",Long.class),1L,null,body,true);
 }
 String etag(ArticleBO value) { return StrongEtag.of("article",value.id(),value.revision()); }
 void forbidden(org.junit.jupiter.api.function.Executable action) { assertEquals(403,assertThrows(ContractProblemException.class,action).getStatus().value()); }
 @Configuration
 static class Config extends ArticleDatabaseTest.Config {
  @Override @Bean SqlSessionFactory sessions(DataSource source) throws Exception {
   var factory=super.sessions(source); var resource=new ClassPathResource("mapper/RbacMapper.xml");
   try(var stream=resource.getInputStream()) { new XMLMapperBuilder(stream,factory.getConfiguration(),resource.toString(),factory.getConfiguration().getSqlFragments()).parse(); }
   factory.getConfiguration().setCacheEnabled(false); return factory;
  }
  @Bean MapperFactoryBean<RbacMapper> rbac(SqlSessionFactory factory) { return mapper(RbacMapper.class,factory); }
  @Bean RbacCatalog catalog() { return RbacIntegrationCatalog.forArticle(); }
  @Bean RbacAuthorizationReader reader(RbacMapper mapper,RbacCatalog catalog) { return new RbacAuthorizationReader(mapper,catalog,new AdminPermissionPolicy()); }
  @Bean @Primary ArticleAuthorization assignmentAuth(RbacAuthorizationReader reader) { return new ArticleAuthorization(reader); }
 }
}
