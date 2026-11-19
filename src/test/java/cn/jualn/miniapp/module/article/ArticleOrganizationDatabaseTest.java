package cn.jualn.miniapp.module.article;

import cn.jualn.miniapp.module.authorization.service.RbacAuthorizationReader;
import cn.jualn.miniapp.module.authorization.service.RbacCatalog;
import cn.jualn.miniapp.module.authorization.mapper.RbacMapper;
import cn.jualn.miniapp.module.authorization.service.TransactionalAuthorization;
import cn.jualn.miniapp.module.authorization.service.UnifiedAuthorizationService;
import cn.jualn.miniapp.module.authorization.service.UnifiedDutyCatalog;
import cn.jualn.miniapp.module.authorization.service.UnifiedDutyReader;
import cn.jualn.miniapp.module.authorization.mapper.UnifiedMapper;
import cn.jualn.miniapp.module.authorization.model.UnifiedModels;

import cn.jualn.miniapp.module.person.service.facts.PersonCatalog;
import cn.jualn.miniapp.module.person.service.access.PersonBusinessScopes;
import cn.jualn.miniapp.module.person.service.access.PersonAuthorityGate;

import cn.dev33.satoken.SaManager;
import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.common.exception.*;
import cn.jualn.miniapp.common.security.AdminStpUtil;
import cn.jualn.miniapp.common.web.StrongEtag;
import cn.jualn.miniapp.module.admin.auth.support.AdminPermissionPolicy;
import cn.jualn.miniapp.module.article.bo.*;
import cn.jualn.miniapp.module.article.controller.ArticleController;
import cn.jualn.miniapp.module.article.controller.admin.*;
import cn.jualn.miniapp.module.article.converter.ArticleConverter;
import cn.jualn.miniapp.module.article.mapper.ArticleMapper;
import cn.jualn.miniapp.module.article.service.*;
import cn.jualn.miniapp.module.person.mapper.*;
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
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real MySQL, unified preview/commit, provenance Reader, Article Writer, audit and MVC.
 * Admin session, text/media safety, COS, notifications and Redis are explicit test doubles. */
@EnabledIfSystemProperty(named="event.test.jdbcUrl",matches="jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/article_contract_test")
class ArticleOrganizationDatabaseTest {
 static final List<Map<String,Object>> evidence=new ArrayList<>();
 static final AtomicLong ids=new AtomicLong(System.currentTimeMillis());
 AnnotationConfigApplicationContext context;JdbcTemplate db;ArticleService articles;UnifiedAuthorizationService unified;
 ArticleOrganizationPolicy policy;ArticleSafetyChecks safety;MockMvc mvc;long root,editor;String a,b,rootAssignment;long category;
 @BeforeEach void start() throws Exception {
  context=new AnnotationConfigApplicationContext(Config.class);db=new JdbcTemplate(context.getBean(DataSource.class));articles=context.getBean(ArticleService.class);
  unified=context.getBean(UnifiedAuthorizationService.class);policy=context.getBean("realPolicy",ArticleOrganizationPolicy.class);safety=context.getBean(ArticleSafetyChecks.class);
  db.update("UPDATE rbac_permission_definition SET lifecycle='ENABLED',readiness='READY',grantable=1,unavailable_reason=NULL");
  db.update("UPDATE rbac_role SET lifecycle='ENABLED',readiness='READY',grantable=1,unavailable_reason=NULL");
  db.update("DELETE FROM article_publisher_migration_evidence");db.update("DELETE FROM article_organization_audit");db.update("DELETE FROM article_publisher_organization_mapping");db.update("DELETE FROM event_attachment_link WHERE target_type=8");db.update("DELETE FROM article");db.update("DELETE FROM article_category WHERE parent_id IS NOT NULL");db.update("DELETE FROM article_category WHERE scene_codes IS NULL OR JSON_LENGTH(scene_codes)=0"); db.update("UPDATE article_category SET enabled=TRUE");db.update("DELETE FROM article_publisher");
  root=user();editor=user();rootAssignment=assignment(root,"ROOT","AUTHORIZATION");a=org("Same name");b=org("Same name");
  db.update("INSERT INTO article_category(name,display_order,enabled,created_by,updated_by,created_at,updated_at) VALUES(?,0,1,?,?,NOW(6),NOW(6))",UUID.randomUUID().toString(),root,root);
  category=db.queryForObject("SELECT MAX(id) FROM article_category",Long.class);
  var storage=mock(cn.dev33.satoken.context.model.SaStorage.class);Map<String,Object> values=new HashMap<>();
  when(storage.get(anyString())).thenAnswer(i->values.get(i.getArgument(0)));when(storage.set(anyString(),any())).thenAnswer(i->{values.put(i.getArgument(0),i.getArgument(1));return storage;});when(storage.delete(anyString())).thenAnswer(i->{values.remove(i.getArgument(0));return storage;});
  SaManager.getSaTokenContext().setContext(mock(cn.dev33.satoken.context.model.SaRequest.class),mock(cn.dev33.satoken.context.model.SaResponse.class),storage);use(editor);
  var json=new ObjectMapper().addMixIn(org.springframework.http.ProblemDetail.class,org.springframework.http.converter.json.ProblemDetailJacksonMixin.class);
  mvc=MockMvcBuilders.standaloneSetup(new AdminArticleController(articles,new ArticleConverter()),new ArticleController(articles,new ArticleConverter()),new ArticleOrganizationController(policy))
   .setControllerAdvice(new GlobalExceptionHandler()).setMessageConverters(new MappingJackson2HttpMessageConverter(json)).build();
 }
 @AfterEach void close() throws Exception {UserContext.clear();SaManager.getSaTokenContext().clearContext();context.close();java.nio.file.Files.createDirectories(java.nio.file.Path.of("target"));new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(java.nio.file.Path.of("target/article-org-http-responses.json").toFile(),evidence);}
 void record(String schema,org.springframework.mock.web.MockHttpServletResponse response)throws Exception {evidence.add(Map.of("schema",schema,"status",response.getStatus(),"body",new ObjectMapper().readTree(response.getContentAsByteArray())));}
 long user(){long id=ids.incrementAndGet();db.update("INSERT INTO user_profile(id,openid,status) VALUES(?,?,1)",id,"article-org-"+id);db.update("INSERT INTO rbac_authorization_authority(user_id,source,version,updated_at) VALUES(?,'ASSIGNMENT',?,UTC_TIMESTAMP(6))",id,key());return id;}
 String key(){return UUID.randomUUID().toString();}
 String assignment(long user,String role,String namespace){String id=key();db.update("INSERT INTO rbac_user_role_assignment(id,user_id,role_code,namespace,scope_type,granted_at,version,grant_change_id) VALUES(?,?,?,?,'GLOBAL',UTC_TIMESTAMP(6)-INTERVAL 5 SECOND,?,?)",id,user,role,namespace,key(),key());return id;}
 String org(String name){String id=key(),event=key();db.update("INSERT INTO person_foundation_event(id,aggregate_type,aggregate_id,aggregate_version,operation,after_json,operator_user_id,recorded_at,reason,verification_reference,source,receipt_id) VALUES(?,'ORGANIZATION',?,1,'createPersonOrganization','{}',?,UTC_TIMESTAMP(6),'synthetic','test-only','LOCAL_MANUAL',?)",event,id,root,key());db.update("INSERT INTO person_organization(id,name,description,status,revision,created_at,updated_at,last_change_id) VALUES(?,?,'','ACTIVE',1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),?)",id,name,event);return id;}
 UnifiedModels.Selection selection(String duty,String org){return new UnifiedModels.Selection(duty,new UnifiedModels.Target("ORGANIZATION",null,org),"2099-01-01T00:00:00Z",null);}
 UnifiedModels.Receipt grant(long user,String duty,String org){return grantBy(root,user,selection(duty,org),rootAssignment);}
 UnifiedModels.Receipt grantBy(long actor,long user,UnifiedModels.Selection selection,String authority){var intent=new UnifiedModels.Intent("GRANT",Long.toString(user),List.of(selection),authority,null,null,"Synthetic approved duty");var preview=unified.preview(actor,intent,key());return unified.commit(actor,new UnifiedModels.Commit(preview.previewId(),intent),key());}
 void revoke(String source,String id){var intent=new UnifiedModels.Intent("REVOKE",null,null,null,source,id,"Synthetic withdrawal");var p=unified.preview(root,intent,key());unified.commit(root,new UnifiedModels.Commit(p.previewId(),intent),key());}
 void use(long actor){AdminStpUtil.STP_LOGIC.switchTo(actor);UserContext.setUserId(actor);}
 ArticleSaveBO command(String org){var body=new ObjectMapper().createObjectNode().put("format","ARTICLE_RICH_TEXT").put("version",1);body.putArray("nodes").addObject().put("type","PARAGRAPH").putArray("runs").addObject().put("text","Article body");return new ArticleSaveBO("Article","",category,null,null,body,true,org);}
 String tag(ArticleBO row){return StrongEtag.of("article",row.id(),row.revision());}
 ArticleBO live(String org){var row=articles.create(command(org));return articles.publish(row.id(),tag(row));}
 void problem(String type,org.junit.jupiter.api.function.Executable action){assertEquals("/problems/"+type,assertThrows(ContractProblemException.class,action).getType());}

 @Test void firstCreateWithoutPublisherAndExactOrganizationFiltering() throws Exception {
  assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM article_publisher",Integer.class));
  use(root);assertNotNull(articles.createCategory(new ArticleCategorySaveBO("No Publisher needed "+key(),0,true)).id());use(editor);
  grant(editor,"ARTICLE_EDIT",a);grant(editor,"ARTICLE_PUBLISH",a);
  var draft=articles.create(command(a));
  assertEquals(editor,db.queryForObject("SELECT actor FROM article_organization_audit WHERE article_id=?",Long.class,draft.id()));
  assertEquals("ARTICLE_EDIT",db.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(authorization_sources,'$[0].dutyCode')) FROM article_organization_audit WHERE article_id=?",String.class,draft.id()));
  assertEquals(a,draft.publisherOrgId());assertNull(draft.publisherId());assertNull(draft.publisherNameSnapshot());
  record("ArticleAdminDetail",mvc.perform(get("/v1/admin/articles/"+draft.id())).andExpect(status().isOk()).andExpect(jsonPath("$.publisherOrgId").value(a)).andExpect(jsonPath("$.publisherId").doesNotExist()).andReturn().getResponse());
  record("ArticlePublisherOrganizationPage",mvc.perform(get("/v1/admin/article-publisher-organizations")).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].organizationId").value(a)).andReturn().getResponse());
  problem("not-found",()->articles.create(command(b)));
  mvc.perform(get("/v1/admin/articles").param("publisherOrgId",b)).andExpect(status().isNotFound());
  mvc.perform(get("/v1/admin/articles").param("publisherOrgId",a).param("publisherId","1")).andExpect(status().isBadRequest());
  grant(editor,"ARTICLE_EDIT",b);grant(editor,"ARTICLE_PUBLISH",b);var second=articles.create(command(b));
  var published=articles.publish(draft.id(),tag(draft));
  record("ArticleDetail",mvc.perform(get("/v1/articles/"+published.id())).andExpect(status().isOk()).andExpect(jsonPath("$.publisherNameSnapshot").value("Same name")).andExpect(jsonPath("$.publisher").doesNotExist()).andReturn().getResponse());
  record("ArticlePage",mvc.perform(get("/v1/articles")).andExpect(status().isOk()).andReturn().getResponse());
  record("ArticleAdminPage",mvc.perform(get("/v1/admin/articles")).andExpect(status().isOk()).andReturn().getResponse());
  assertEquals(2,policy.candidates(50,null).items().size());assertEquals(1,articles.listAdmin(1,20,null,null,null,null,a,null,"-updatedAt").totalItems());
  assertTrue(db.queryForObject("SELECT COUNT(*) FROM article_organization_audit WHERE article_id IN (?,?)",Integer.class,draft.id(),second.id())>=2);
 }
 @Test void rootManagesAllOrganizationsWithoutSelfGrantButStillChecksStateAndRevocation() throws Exception {
  use(root);
  assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM person_business_scope WHERE grantee_user_id=?",Integer.class,root));
  var available=new HashSet<String>();String next=null;
  do {var page=policy.candidates(50,next);page.items().forEach(c->available.add(c.organizationId()));next=page.nextCursor();}while(next!=null);
  assertTrue(available.contains(a));assertTrue(available.contains(b));
  assertTrue(unified.dutySummary(root,List.of("ARTICLE_EDIT","ARTICLE_PUBLISH")).items().stream().allMatch(i->i.effective()));
  assertTrue(unified.effective(root,root,50,null).items().isEmpty());
  var first=articles.create(command(a));var second=articles.create(command(b));
  assertEquals(2,articles.listAdmin(1,20,null,null,null,null,null,null,"-updatedAt").totalItems());
  var live=articles.publish(first.id(),tag(first));assertEquals("Same name",live.publisherNameSnapshot());
  assertEquals(rootAssignment,db.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(authorization_sources,'$[0].rootAssignmentId')) FROM article_organization_audit WHERE article_id=? AND operation='PUBLISH'",String.class,first.id()));
  db.update("UPDATE person_organization SET status='INACTIVE' WHERE id=?",b);
  problem("article-publisher-organization-inactive",()->articles.publish(second.id(),tag(second)));
  db.update("UPDATE rbac_user_role_assignment SET revoked_at=UTC_TIMESTAMP(6),revoke_change_id=?,current_slot=NULL,version=? WHERE id=?",key(),key(),rootAssignment);
  mvc.perform(get("/v1/admin/articles/"+first.id())).andExpect(status().isForbidden());
  assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM person_business_scope WHERE grantee_user_id=?",Integer.class,root));
 }
 @Test void wireRejectsForgedSignatureAndNamespaceConfusion() throws Exception {
  grant(editor,"ARTICLE_EDIT",a);
  var json=new ObjectMapper();var request=(com.fasterxml.jackson.databind.node.ObjectNode)json.valueToTree(command(a));request.remove(List.of("publisherId","coverAttachmentId"));request.put("categoryId",Long.toString(category));
  var forged=request.deepCopy().put("publisherNameSnapshot","Forged");mvc.perform(post("/v1/admin/articles").contentType("application/json").content(forged.toString())).andExpect(status().isBadRequest());
  for(var invalid:List.of(request.deepCopy().putNull("publisherOrgId"),request.deepCopy().put("publisherOrgId",1)))mvc.perform(post("/v1/admin/articles").contentType("application/json").content(invalid.toString())).andExpect(status().isBadRequest());
  mvc.perform(post("/v1/admin/articles").contentType("application/json").content(request.toString())).andExpect(status().isCreated()).andExpect(jsonPath("$.publisherOrgId").value(a));
  var invalidTarget=new UnifiedModels.Selection("ARTICLE_EDIT",new UnifiedModels.Target("PLATFORM",null,null),"2099-01-01T00:00:00Z",null);
  assertThrows(ContractProblemException.class,()->grantBy(root,user(),invalidTarget,rootAssignment));
 }
 @Test void snapshotsFreezeOnPublishChangeAndRepublishOnly() {
  for(String org:List.of(a,b)){grant(editor,"ARTICLE_EDIT",org);grant(editor,"ARTICLE_PUBLISH",org);}
  var row=live(a);assertEquals("Same name",row.publisherNameSnapshot());var firstPublished=row.publishedAt();
  db.update("UPDATE person_organization SET name='Renamed' WHERE id=?",a);
  var edited=articles.replace(row.id(),command(a),tag(row));assertEquals("Same name",edited.publisherNameSnapshot());
  var hidden=articles.unpublish(row.id(),tag(edited));assertEquals("Same name",hidden.publisherNameSnapshot());
  var again=articles.publish(row.id(),tag(hidden));assertEquals("Renamed",again.publisherNameSnapshot());assertEquals(firstPublished,again.publishedAt());
  db.update("UPDATE person_organization SET name='Organization B' WHERE id=?",b);
  var moved=articles.replace(row.id(),command(b),tag(again));assertEquals(b,moved.publisherOrgId());assertEquals("Organization B",moved.publisherNameSnapshot());assertEquals(firstPublished,moved.publishedAt());
  assertEquals("Organization B",articles.getPublic(row.id()).publisherNameSnapshot());
 }
 @Test void sourceAndDestinationBothNeedEditAndPublishSources() {
  grant(editor,"ARTICLE_EDIT",a);grant(editor,"ARTICLE_PUBLISH",a);var row=live(a);
  problem("not-found",()->articles.replace(row.id(),command(b),tag(row)));
  grant(editor,"ARTICLE_EDIT",b);problem("not-found",()->articles.replace(row.id(),command(b),tag(row)));
  assertEquals(a,articles.getAdmin(row.id()).publisherOrgId());assertEquals(row.revision(),articles.getAdmin(row.id()).revision());
  grant(editor,"ARTICLE_PUBLISH",b);assertEquals(b,articles.replace(row.id(),command(b),tag(row)).publisherOrgId());
 }
 @Test void globalRoleStaysScopedWhileRootNeedsNoSelfGrant() {
  assignment(editor,"CONTENT_EDITOR","ARTICLE");assertTrue(policy.candidates(20,null).items().isEmpty());
  problem("no-authorized-publisher-organization",()->articles.create(command(a)));
  use(root);assertFalse(policy.candidates(20,null).items().isEmpty());assertEquals(a,articles.create(command(a)).publisherOrgId());
  assertThrows(ContractProblemException.class,()->grantBy(root,root,selection("ARTICLE_EDIT",a),rootAssignment));
 }
 @Test void revokedDelegationDoesNotKillIndependentSourceAndCursorChanges() {
  long manager=user();var selected=selection("ARTICLE_EDIT",a);
  var authority=unified.grantAuthority(root,new UnifiedModels.AuthorityRequest(Long.toString(manager),List.of(selected),"INDEPENDENT_APPROVAL",selected.expiresAt(),"Synthetic limited delegation"),key());
  grantBy(manager,editor,selected,authority.authorityId());var independent=grant(editor,"ARTICLE_EDIT",a);grant(editor,"ARTICLE_EDIT",b);
  var page=policy.candidates(1,null);assertNotNull(page.nextCursor());
  revoke("DELEGATION_AUTHORITY",authority.authorityId());assertNotNull(articles.create(command(a)));
  problem("invalid-cursor",()->policy.candidates(1,page.nextCursor()));
  revoke("SCOPE",independent.scopeGrantIds().get(0));problem("not-found",()->articles.create(command(a)));
 }
 @Test void inactiveOrganizationDoesNotUnpublishHistoryAndCanBeWithdrawn() {
  grant(editor,"ARTICLE_EDIT",a);grant(editor,"ARTICLE_PUBLISH",a);var row=live(a);
  db.update("UPDATE person_organization SET status='INACTIVE' WHERE id=?",a);
  assertEquals("Same name",articles.getPublic(row.id()).publisherNameSnapshot());assertTrue(policy.candidates(20,null).items().isEmpty());
  problem("article-publisher-organization-inactive",()->articles.create(command(a)));
  problem("article-publisher-organization-inactive",()->articles.replace(row.id(),command(a),tag(row)));
  var hidden=articles.unpublish(row.id(),tag(row));assertEquals("Same name",hidden.publisherNameSnapshot());
  problem("article-publisher-organization-inactive",()->articles.publish(row.id(),tag(hidden)));
 }
 @Test void revokedAfterSafetyAndAuditFailureBothRollbackContent() {
  var edit=grant(editor,"ARTICLE_EDIT",a);grant(editor,"ARTICLE_PUBLISH",a);var draft=articles.create(command(a));
  doAnswer(call->{revoke("SCOPE",edit.scopeGrantIds().get(0));return null;}).when(safety).text(eq(editor),anyString());
  // Publishing needs the independent publish source, not an edit source.
  var row=articles.publish(draft.id(),tag(draft));assertEquals("PUBLISHED",row.publishStatus());
  reset(safety);grant(editor,"ARTICLE_EDIT",a);
  db.execute("CREATE TRIGGER article_org_audit_failure BEFORE INSERT ON article_organization_audit FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic audit unavailable'");
  try {assertThrows(RuntimeException.class,()->articles.replace(row.id(),command(a),tag(row)));}
  finally {db.execute("DROP TRIGGER article_org_audit_failure");}
  assertEquals(row.revision(),articles.getAdmin(row.id()).revision());
 }
 @Test void unknownSourceCannotBecomeAnEmptyCandidateOrSuccessfulWrite() throws Exception {
  grant(editor,"ARTICLE_EDIT",a);
  db.update("DELETE FROM rbac_authorization_authority WHERE user_id=?",editor);
  problem("article-organization-authorization-unavailable",()->policy.candidates(20,null));
  problem("article-organization-authorization-unavailable",()->articles.create(command(a)));
  assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM article",Integer.class));
 }
 @Test void publicationRejectsRevocationAndRenameBetweenSafetyAndCommit() {
  grant(editor,"ARTICLE_EDIT",a);var publish=grant(editor,"ARTICLE_PUBLISH",a);var draft=articles.create(command(a));
  doAnswer(call->{revoke("SCOPE",publish.scopeGrantIds().get(0));return null;}).when(safety).text(eq(editor),anyString());
  assertThrows(ContractProblemException.class,()->articles.publish(draft.id(),tag(draft)));assertEquals("DRAFT",articles.getAdmin(draft.id()).publishStatus());
  reset(safety);grant(editor,"ARTICLE_PUBLISH",a);
  doAnswer(call->{db.update("UPDATE person_organization SET name='Race rename' WHERE id=?",a);return null;}).when(safety).text(eq(editor),anyString());
  problem("article-organization-authorization-unavailable",()->articles.publish(draft.id(),tag(draft)));assertEquals(draft.revision(),articles.getAdmin(draft.id()).revision());
 }
 @Test void legacyUnmappedReadAndWriteAndRequestNamespacesRemainDistinct() throws Exception {
  grant(editor,"ARTICLE_EDIT",a);grant(editor,"ARTICLE_PUBLISH",a);
  db.update("INSERT INTO article_publisher VALUES(?, 'Legacy label',1,?,NOW(6))",editor,editor);
  db.update("INSERT INTO article(title,summary,body,plain_text,category_id,publisher_id,publish_status,created_by,updated_by,created_at,updated_at,published_at,last_published_at) VALUES('Legacy','',?,'Article body',?,?,'PUBLISHED',?,?,NOW(6),NOW(6),NOW(6),NOW(6))",command(a).body().toString(),category,editor,editor,editor);
  long id=db.queryForObject("SELECT MAX(id) FROM article",Long.class);assertEquals("Legacy label",articles.getPublic(id).publisher().displayName());
  record("ArticleDetail",mvc.perform(get("/v1/articles/"+id)).andExpect(status().isOk()).andExpect(jsonPath("$.publisher.displayName").value("Legacy label")).andReturn().getResponse());
  record("ArticleAdminDetail",mvc.perform(get("/v1/admin/articles/"+id)).andExpect(status().isOk()).andReturn().getResponse());
  problem("article-publisher-migration-required",()->articles.replace(id,command(a),StrongEtag.of("article",id,1L)));
  assertNull(articles.getAdmin(id).publisherOrgId());assertEquals(editor,articles.getAdmin(id).publisherId());
  var json=new ObjectMapper();var body=json.valueToTree(command(a));
  ((com.fasterxml.jackson.databind.node.ObjectNode)body).remove("publisherId");((com.fasterxml.jackson.databind.node.ObjectNode)body).put("publisherId",Long.toString(editor));
  mvc.perform(post("/v1/admin/articles").contentType("application/json").content(body.toString())).andExpect(status().isBadRequest());
  var legacy=new ArticleSaveBO("Legacy request","",category,editor,null,command(a).body(),true);
  problem("article-publisher-migration-required",()->articles.create(legacy));
  db.update("INSERT INTO article_publisher_organization_mapping VALUES(?,?,?,UTC_TIMESTAMP(6),'synthetic approved mapping')",editor,a,root);
  assertEquals(a,articles.create(legacy).publisherOrgId());assertNull(articles.getPublic(id).publisherOrgId());
  db.update("UPDATE article SET publisher_org_id=?,publisher_name_snapshot='Guessed current label' WHERE id=?",a,id);
  assertNull(articles.getPublic(id).publisherOrgId());problem("article-publisher-migration-required",()->articles.unpublish(id,StrongEtag.of("article",id,1L)));
  db.update("INSERT INTO article_publisher_migration_evidence VALUES(?,?,'Verified original signature',?,UTC_TIMESTAMP(6),'Synthetic historical signature proof')",id,a,root);
  db.update("UPDATE article SET publisher_name_snapshot='Verified original signature' WHERE id=?",id);
  assertEquals("Verified original signature",articles.getPublic(id).publisherNameSnapshot());
  var edited=articles.replace(id,command(a),StrongEtag.of("article",id,1L));assertEquals("Verified original signature",edited.publisherNameSnapshot());
 }

 @Test void learningOptionsUseOnlyTaskScopeAndAllowCrossOrganizationPublicMaterial() throws Exception {
  grant(editor,"ARTICLE_EDIT",a);grant(editor,"ARTICLE_PUBLISH",a);grant(editor,"ARTICLE_EDIT",b);grant(editor,"ARTICLE_PUBLISH",b);
  var first=live(a);var second=live(b);long learner=user();grant(learner,"LEARNING_MANAGE",a);use(learner);
  var options=context.getBean(cn.jualn.miniapp.module.learningtask.service.LearningTaskArticleOptions.class);
  assertThrows(RuntimeException.class,()->articles.getAdmin(first.id()));
  var page=options.list(null,"ORGANIZATION",a,null,null,1);assertEquals(1,page.items().size());assertNotNull(page.nextCursor());
  var next=options.list(null,"ORGANIZATION",a,null,page.nextCursor(),1);assertEquals(1,next.items().size());assertNotEquals(page.items().get(0).articleId(),next.items().get(0).articleId());
  assertEquals(2,options.list(null,"ORGANIZATION",a,null,null,20).items().size());
  var optionMvc=MockMvcBuilders.standaloneSetup(new cn.jualn.miniapp.module.learningtask.controller.LearningTaskArticleOptionsController(options)).setMessageConverters(new MappingJackson2HttpMessageConverter(new ObjectMapper())).build();
  record("ArticleReferenceOptionPage",optionMvc.perform(get("/v1/admin/learning-task-article-options").param("targetType","ORGANIZATION").param("organizationId",a)).andExpect(status().isOk()).andReturn().getResponse());
  assertThrows(ContractProblemException.class,()->options.list(null,"ORGANIZATION",b,null,null,20));
  assertThrows(ContractProblemException.class,()->options.list(null,"PLATFORM",a,null,null,20));
  grant(learner,"LEARNING_MANAGE",b);
  assertEquals("/problems/invalid-cursor",assertThrows(ContractProblemException.class,()->options.list(null,"ORGANIZATION",a,null,page.nextCursor(),1)).getType());
  use(editor);articles.unpublish(second.id(),tag(second));use(learner);
  assertEquals(1,options.list(null,"ORGANIZATION",a,null,null,20).items().size());
 }
 @Test void mediaSingleBatchAndOwnListEnforceExactArticleOrganization() {
  grant(editor,"ARTICLE_EDIT",a);grant(editor,"ARTICLE_PUBLISH",a);var editB=grant(editor,"ARTICLE_EDIT",b);var publishB=grant(editor,"ARTICLE_PUBLISH",b);
  assertTrue(unified.dutySummary(root,List.of("ARTICLE_EDIT","ARTICLE_PUBLISH")).items().stream().allMatch(i->i.effective()));
  assertTrue(unified.effective(root,root,50,null).items().isEmpty());
  var first=articles.create(command(a));var second=articles.create(command(b));
  String object="article-resource/"+editor+"/"+key();
  db.update("INSERT INTO media_attachment(type,kind,registered,registered_by,object_key,url,original_name,sort_order,content_type,size_bytes,width,height,verified,retention) VALUES(1,'IMAGE',1,?,?,?,'Image',0,'image/png',100,2,2,1,'FORMAL')",editor,object,"https://cdn.example/"+object);
  long file=db.queryForObject("SELECT id FROM media_attachment WHERE object_key=?",Long.class,object);
  db.update("INSERT INTO event_attachment_link(target_type,target_id,attachment_id,display_order) VALUES(8,?,?,0)",second.id(),file);
  // Revoke only B's business scope; uploader status and media capability remain.
  for(String scope:editB.scopeGrantIds()) revoke("SCOPE",scope);
  for(String scope:publishB.scopeGrantIds()) revoke("SCOPE",scope);
  var access=context.getBean(cn.jualn.miniapp.module.media.service.MediaAccess.class);
  assertThrows(RuntimeException.class,()->access.authorizeReusableRead(file,editor));
  assertEquals("UNAVAILABLE",access.readAdminAttachments(List.of(file),editor).get(0).status());
  var management=context.getBean(cn.jualn.miniapp.module.media.service.AttachmentManagementService.class);
  assertEquals(0,management.list(editor,1,20).totalItems());
  db.update("INSERT INTO event_attachment_link(target_type,target_id,attachment_id,display_order) VALUES(8,?,?,0)",first.id(),file);
  access.authorizeReusableRead(file,editor);assertEquals("READY",access.readAdminAttachments(List.of(file),editor).get(0).status());
  assertEquals(List.of(file),management.list(editor,1,20).items().stream().map(i->i.getId()).toList());assertEquals(1,management.list(editor,1,20).totalItems());
 }
 @Test void deletionAndRestorationRetainCurrentOrganizationAuthorizationAndAudit() {
  grant(editor,"ARTICLE_EDIT",a);grant(editor,"ARTICLE_PUBLISH",a);var live=live(a);var hidden=articles.unpublish(live.id(),tag(live));
  db.update("UPDATE person_organization SET status='INACTIVE' WHERE id=?",a);
  articles.delete(hidden.id(),tag(hidden));var deleted=articles.getAdmin(hidden.id());assertEquals("UNPUBLISHED",deleted.publishStatus());assertNotNull(deleted.deletedAt());
  var restored=articles.restore(hidden.id(),tag(deleted));assertEquals("UNPUBLISHED",restored.publishStatus());assertNull(restored.deletedAt());
  assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM article_organization_audit WHERE article_id=? AND operation='DELETE'",Integer.class,live.id()));
  assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM article_organization_audit WHERE article_id=? AND operation='RESTORE'",Integer.class,live.id()));
  assertFalse(articles.isPubliclyVisible(live.id()));
 }

 @Test void foundationExpiryWhilePersistingAuditRollsBackArticleAndItsAudit() {
  var at=db.queryForObject("SELECT UTC_TIMESTAMP(6)",java.time.LocalDateTime.class).plusSeconds(3);
  var selected=new UnifiedModels.Selection("ARTICLE_EDIT",new UnifiedModels.Target("ORGANIZATION",null,a),at.atOffset(java.time.ZoneOffset.UTC).toString(),null);
  grantBy(root,editor,selected,rootAssignment);
  long count=db.queryForObject("SELECT COUNT(*) FROM article",Long.class);
  db.execute("CREATE TRIGGER foundation_article_audit_delay BEFORE INSERT ON article_organization_audit FOR EACH ROW DO SLEEP(5)");
  long start=System.nanoTime();
  try { assertThrows(cn.jualn.miniapp.common.exception.ContractProblemException.class,()->articles.create(command(a))); }
  finally {db.execute("DROP TRIGGER foundation_article_audit_delay");}
  assertTrue(java.time.Duration.ofNanos(System.nanoTime()-start).toMillis()>=4500,"The write must reach the real audit delay before expiry");
  assertEquals(count,db.queryForObject("SELECT COUNT(*) FROM article",Long.class));
  assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM article_organization_audit",Integer.class));
 }

 @Configuration @Import({cn.jualn.miniapp.module.person.service.access.PersonAuthorityGate.class,cn.jualn.miniapp.module.admin.operation.AdminAuditTestConfiguration.class,PersonBusinessScopes.class,PersonCatalog.class,TransactionalAuthorization.class,UnifiedDutyCatalog.class,UnifiedDutyReader.class,UnifiedAuthorizationService.class,
 cn.jualn.miniapp.module.learningtask.service.LearningTaskArticleOptions.class,cn.jualn.miniapp.module.learningtask.service.LearningTaskScopePolicy.class,cn.jualn.miniapp.module.learningtask.service.LearningTaskAuthorizationPolicy.class,cn.jualn.miniapp.module.article.service.impl.ArticleReferenceReadServiceImpl.class,cn.jualn.miniapp.module.user.service.UserCurrentLoginAccess.class})
 static class Config extends ArticleDatabaseTest.Config {
  @Override @Bean SqlSessionFactory sessions(DataSource source)throws Exception {
   var factory=super.sessions(source);for(String name:List.of("RbacMapper","PersonMapper","PersonBusinessMapper","UnifiedMapper","ManagedPersonMapper","UserProfileMapper")) {var resource=new ClassPathResource("mapper/"+name+".xml");try(var in=resource.getInputStream()){new XMLMapperBuilder(in,factory.getConfiguration(),resource.toString(),factory.getConfiguration().getSqlFragments()).parse();}}
   var paging=new com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor();paging.addInnerInterceptor(new com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor(com.baomidou.mybatisplus.annotation.DbType.MYSQL));factory.getConfiguration().addInterceptor(paging);
   factory.getConfiguration().setCacheEnabled(false);return factory;
  }
  @Bean MapperFactoryBean<cn.jualn.miniapp.module.person.mapper.ManagedPersonMapper> managedPersonMapper(SqlSessionFactory s){return mapper(cn.jualn.miniapp.module.person.mapper.ManagedPersonMapper.class,s);}
  @Bean cn.jualn.miniapp.module.authorization.service.CurrentAuthorization current(RbacAuthorizationReader reader){return new cn.jualn.miniapp.module.authorization.service.CurrentAuthorization(reader);}
  @Bean cn.jualn.miniapp.module.user.service.UserLearningTaskRecipientService recipients(){return mock(cn.jualn.miniapp.module.user.service.UserLearningTaskRecipientService.class);}
  @Bean cn.jualn.miniapp.module.learningtask.service.LearningTaskResponsibilities responsibilities(){return mock(cn.jualn.miniapp.module.learningtask.service.LearningTaskResponsibilities.class);}
  @Bean MapperFactoryBean<cn.jualn.miniapp.module.user.mapper.UserProfileMapper> userProfileMapper(SqlSessionFactory s){return mapper(cn.jualn.miniapp.module.user.mapper.UserProfileMapper.class,s);}
  @Bean MapperFactoryBean<RbacMapper> rbacMapper(SqlSessionFactory s){return mapper(RbacMapper.class,s);}
  @Bean MapperFactoryBean<UnifiedMapper> unifiedMapper(SqlSessionFactory s){return mapper(UnifiedMapper.class,s);}
  @Bean MapperFactoryBean<PersonMapper> personMapper(SqlSessionFactory s){return mapper(PersonMapper.class,s);}
  @Bean MapperFactoryBean<PersonBusinessMapper> businessMapper(SqlSessionFactory s){return mapper(PersonBusinessMapper.class,s);}
  @Bean cn.jualn.miniapp.module.media.service.AttachmentManagementService attachmentManagement(cn.jualn.miniapp.module.authorization.service.CurrentAuthorization auth,cn.jualn.miniapp.module.media.mapper.MediaAttachmentMapper attachments,cn.jualn.miniapp.module.media.mapper.MediaUploadRecordMapper uploads,cn.jualn.miniapp.module.media.converter.MediaConverter converter) {return new cn.jualn.miniapp.module.media.service.AttachmentManagementService(auth,attachments,uploads,converter,mock(cn.jualn.miniapp.module.content.service.AttachmentReferenceReader.class),mock(cn.jualn.miniapp.module.audit.service.AttachmentHistoryReader.class));}
  @Bean RbacCatalog catalog()throws Exception {var constructor=RbacCatalog.class.getDeclaredConstructor(Set.class);constructor.setAccessible(true);var codes=new HashSet<String>();try(var in=new ClassPathResource("rbac/rbac-v1.json").getInputStream()){for(var n:new ObjectMapper().readTree(in).get("permissions"))codes.add(n.get("code").asText());}return constructor.newInstance(codes);}
  @Bean RbacAuthorizationReader reader(RbacMapper mapper,RbacCatalog catalog){return new RbacAuthorizationReader(mapper,catalog,new AdminPermissionPolicy());}
  @Bean @Primary ArticleAuthorization actualAuthorization(RbacAuthorizationReader reader){return new ArticleAuthorization(reader);}
  @Bean @Primary ArticleOrganizationPolicy realPolicy(PersonBusinessScopes p,TransactionalAuthorization a,UnifiedDutyReader d,ArticleMapper m){return new ArticleOrganizationPolicy(p,a,d,m);}
 }
}
