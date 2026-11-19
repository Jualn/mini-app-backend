package cn.jualn.miniapp.module.post;

import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.module.post.bo.*;
import cn.jualn.miniapp.module.post.mapper.*;
import cn.jualn.miniapp.module.post.service.*;
import cn.jualn.miniapp.module.media.service.*;
import cn.jualn.miniapp.module.user.service.*;
import cn.jualn.miniapp.module.audit.service.*;
import cn.jualn.miniapp.module.audit.bo.AuditReserveResultBO;
import cn.jualn.miniapp.module.authorization.service.CurrentAuthorization;
import cn.jualn.miniapp.module.authorization.model.RbacModels.*;
import cn.jualn.miniapp.module.authorization.model.UnifiedModels.Target;
import cn.jualn.miniapp.module.content.mapper.*;
import cn.jualn.miniapp.module.audit.mapper.ContentAuditLogMapper;
import cn.jualn.miniapp.module.report.mapper.ReportMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.mybatis.spring.SqlSessionTemplate;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real fresh MySQL migration, Mapper predicates, receipt races and rollback. Person/provider facts are explicit doubles. */
@EnabledIfSystemProperty(named="growth.test.jdbcUrl",matches="jdbc:mysql://127[.]0[.]0[.]1:13389/growth_record_test")
class GrowthRecordDatabaseTest {
    static JdbcTemplate db;
    static SqlSessionTemplate sql;
    static DataSourceTransactionManager manager;
    GrowthRecordService service;
    GrowthRecordAccess access;
    GrowthQualification qualification;
    CurrentAuthorization authorization;
    PostMapper posts;
    GrowthRecordMapper links;
    PostSafetyChecks safety;
    Map<Long,String> people;
    Set<String> memberships;
    boolean publishing;
    @BeforeAll static void database() throws Exception {
        var source=new DriverManagerDataSource(System.getProperty("growth.test.jdbcUrl")+"?allowPublicKeyRetrieval=true&sslMode=DISABLED&serverTimezone=Asia/Shanghai","root","");
        var props=new Properties();props.setProperty("sessionVariables","time_zone='+08:00'");source.setConnectionProperties(props);
        db=new JdbcTemplate(source);assertEquals(System.getProperty("growth.test.serverUuid"),db.queryForObject("SELECT @@server_uuid",String.class));
        org.flywaydb.core.Flyway.configure().dataSource(source).load().migrate();
        var factory=new MybatisSqlSessionFactoryBean();factory.setDataSource(source);
        var config=new MybatisConfiguration();config.setMapUnderscoreToCamelCase(true);
        var global=com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils.defaults();global.setMetaObjectHandler(new cn.jualn.miniapp.config.MybatisPlusConfig().metaObjectHandler());global.getDbConfig().setLogicNotDeleteValue("NULL");global.getDbConfig().setLogicDeleteValue("NOW()");
        com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils.setGlobalConfig(config,global);factory.setGlobalConfig(global);
        config.addMapper(GrowthRecordMapper.class);config.addMapper(PostCommunityMapper.class);config.addMapper(GrowthContentMapper.class);config.addMapper(cn.jualn.miniapp.module.media.mapper.GrowthMediaMapper.class);
        factory.setConfiguration(config);factory.setMapperLocations(new PathMatchingResourcePatternResolver().getResources("classpath*:mapper/*.xml"));sql=new SqlSessionTemplate(factory.getObject());manager=new DataSourceTransactionManager(source);
        db.update("INSERT IGNORE INTO user_profile(id,openid,nickname) VALUES(99801,'growth-test-1','one'),(99802,'growth-test-2','two'),(99803,'growth-test-3','three')");
    }
    @BeforeEach void setup() {
        db.update("DELETE FROM report WHERE reporter_id=99802");db.update("DELETE FROM content_safety_review WHERE growth_organization_id='org-A'");db.update("DELETE FROM content_audit_log WHERE target_type=1 AND target_id IN(SELECT id FROM post WHERE user_id IN(99801,99802,99803))");
        db.update("DELETE FROM post_creation_receipt WHERE operation='GROWTH'");db.update("DELETE FROM post_growth_record");db.update("DELETE FROM post WHERE user_id IN(99801,99802,99803)");
        UserContext.setUserId(99801L);people=new ConcurrentHashMap<>();people.put(99801L,"person-A");people.put(99802L,"person-B");memberships=ConcurrentHashMap.newKeySet();memberships.add("person-A/org-A");memberships.add("person-B/org-B");publishing=true;
        posts=sql.getMapper(PostMapper.class);links=sql.getMapper(GrowthRecordMapper.class);qualification=mock(GrowthQualification.class);authorization=mock(CurrentAuthorization.class);var accounts=mock(UserAccountService.class);
        when(accounts.getUserAuthInfo(anyLong())).thenReturn(cn.jualn.miniapp.module.user.bo.UserAuthBO.builder().status(cn.jualn.miniapp.module.user.enums.UserStatus.NORMAL).build());
        when(qualification.currentPerson(anyLong())).thenAnswer(i->people.get(i.<Long>getArgument(0)));
        when(qualification.organizationActive(anyString())).thenReturn(true);
        when(qualification.member(anyLong(),anyString())).thenAnswer(i->memberships.contains(people.get(i.<Long>getArgument(0))+"/"+i.getArgument(1)));
        when(qualification.requirePublisher(anyLong(),anyString(),anyString())).thenAnswer(i->{long user=i.getArgument(0);String org=i.getArgument(1);if(!publishing || !memberships.contains(people.get(user)+"/"+org))throw GrowthProblems.forbidden();return people.get(user);});
        when(qualification.context(anyLong())).thenAnswer(i->{long user=i.getArgument(0);String person=people.get(user);var orgs=publishing?memberships.stream().filter(s->s.startsWith(person+"/")).map(s->new GrowthQualification.Organization(s.substring(s.indexOf('/')+1),"branch",List.of("GROWTH","POLITICAL_BIRTHDAY"))).toList():List.<GrowthQualification.Organization>of();return new GrowthQualification.Context(person,OffsetDateTime.now(),orgs);});
        access=new GrowthRecordAccess(links,posts,qualification,authorization,accounts);var organization=mock(PostOrganizationService.class);when(organization.project(any(),any())).thenReturn(new PostOrganizationService.Projection(Map.of(),Map.of(),Map.of()));
        var files=mock(GrowthMediaService.class);when(files.prepare(any(),any())).thenReturn(List.of());when(files.current(anyLong(),anyString())).thenReturn(List.of());when(files.currentIds(anyLong())).thenReturn(List.of());
        var users=mock(UserService.class);when(users.getSimpleInfo(anyLong())).thenAnswer(i->cn.jualn.miniapp.module.user.bo.UserSimpleBO.builder().id(i.getArgument(0)).nickname("author").build());
        safety=mock(PostSafetyChecks.class);var states=mock(CommunitySafetyStates.class);when(states.current(anyInt(),anyLong(),any(),anyString())).thenAnswer(i->i.getArgument(3));var json=new ObjectMapper().findAndRegisterModules();
        var receipt=new PostCommunityService(posts,sql.getMapper(PostCommunityMapper.class),mock(PostService.class),safety,mock(PostPublicAccess.class),organization,mock(PostCommentPermissions.class),mock(MediaQueries.class),mock(MediaService.class),users,mock(cn.jualn.miniapp.module.interact.service.InteractionLikeQueries.class),accounts,manager,json,states);
        var audit=mock(AuditReservationService.class);when(audit.reserveAuditLogs(any())).thenReturn(AuditReserveResultBO.builder().mediaItems(List.of()).textAuditLogIds(List.of()).build());
        service=new GrowthRecordService(links,sql.getMapper(PostCommunityMapper.class),posts,receipt,access,qualification,files,organization,new PostRepresentationTags(json),safety,states,audit,accounts,users,manager);
    }
    @AfterEach void clear(){UserContext.clear();}
    GrowthRecordBO.Create command(String value){return new GrowthRecordBO.Create("org-A","GROWTH",null,value,List.of(),null,List.of());}
    long create(String key){return service.create(command("accepted"),key).link().postId();}
    @Test void createIsAtomicAndOrdinaryFeedNeverContainsGrowth() {
        long id=create("growth-create-key-0001");assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM post_growth_record WHERE post_id=?",Integer.class,id));
        assertTrue(posts.pageOrganizedPosts(new PostFilterBO("ALL",null,null,null,null,2,null,LocalDateTime.now().minusMinutes(10),20,null)).isEmpty());
        assertEquals(id,create("growth-create-key-0001"));assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM post_growth_record",Integer.class));
        assertEquals("/problems/growth-idempotency-conflict",assertThrows(ContractProblemException.class,()->service.create(command("different"),"growth-create-key-0001")).getType());
    }
    @Test void rollbackLeavesNeitherPostNorReceiptAndSameKeyCanRecover() {
        doAnswer(i->{throw new IllegalStateException("association owner unavailable");}).when(qualification).guard(anyLong());
        assertThrows(IllegalStateException.class,()->create("growth-rollback-key-01"));assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM post_growth_record",Integer.class));assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM post_creation_receipt WHERE operation='GROWTH'",Integer.class));
        doNothing().when(qualification).guard(anyLong());assertTrue(create("growth-rollback-key-01")>0);
    }
    @Test void bindingChangeKeepsPersonHistoryButNeverTransfersWriteRights() {
        long id=create("growth-history-key-01");people.remove(99801L);people.put(99803L,"person-A");
        assertThrows(ContractProblemException.class,()->service.get(id));UserContext.setUserId(99803L);assertEquals(id,service.get(id).link().postId());assertFalse(service.get(id).availableActions().contains("EDIT"));assertThrows(ContractProblemException.class,()->service.editable(id));
        assertEquals(1,service.page(true,null,null,null,null,20).items().size());UserContext.setUserId(99802L);assertThrows(ContractProblemException.class,()->service.get(id));
    }
    @Test void currentMembershipAndHiddenStateAreRecheckedAcrossPagesAndIds() {
        long id=create("growth-member-key-001");memberships.add("person-B/org-A");UserContext.setUserId(99802L);assertEquals(id,service.get(id).link().postId());
        db.update("UPDATE post SET admin_hidden=TRUE WHERE id=?",id);assertThrows(ContractProblemException.class,()->service.get(id));assertTrue(service.page(false,null,"org-A",null,null,20).items().isEmpty());
        UserContext.setUserId(99801L);assertEquals("PRIVATE",service.get(id).lifecycle().visibility());publishing=false;assertFalse(service.get(id).availableActions().contains("EDIT"));assertTrue(service.get(id).availableActions().contains("DELETE"));
    }
    @Test void tiesUsePostIdAndCursorCannotCrossIdentityOrFilters() {
        long one=create("growth-page-key-00001"),two=create("growth-page-key-00002");db.update("UPDATE post SET published_at='2026-11-01 10:00:00' WHERE id IN (?,?)",one,two);
        var first=service.page(true,null,null,null,null,1);assertEquals(Math.max(one,two),first.items().get(0).link().postId());assertTrue(first.hasMore());assertEquals(Math.min(one,two),service.page(true,null,null,null,first.nextCursor(),1).items().get(0).link().postId());
        assertThrows(ContractProblemException.class,()->service.page(true,null,null,"GROWTH",first.nextCursor(),1));
    }
    @Test void exactOrganizationDutyCannotBeReplacedByPlatformOrAnotherSource() {
        long id=create("growth-duty-key-00001");var at=LocalDateTime.now();
        when(authorization.current(99802)).thenReturn(new Decision(99802,Source.ASSIGNMENT,"v","c",at,true,List.of(),List.of(new Grant("platform","POST_MODERATOR_V1","POST","GLOBAL",List.of("content:read","post:dispose"),null,"POST_MODERATE",new Target("PLATFORM",null,null)))));
        assertFalse(access.allowedAdmin(id,99802,"POST_MODERATE","content:read"));
        when(authorization.current(99802)).thenReturn(new Decision(99802,Source.ASSIGNMENT,"v","c",at,true,List.of(),List.of(new Grant("org","POST_MODERATOR_V1","POST","GLOBAL",List.of("content:read"),null,"POST_MODERATE",new Target("ORGANIZATION",null,"org-A")),new Grant("other","POST_MODERATOR_V1","POST","GLOBAL",List.of("post:dispose"),null,"POST_MODERATE",new Target("ORGANIZATION",null,"org-A")))));
        assertTrue(access.allowedAdmin(id,99802,"POST_MODERATE","content:read"));assertFalse(access.allowedAdmin(id,99802,"POST_MODERATE","content:read","post:dispose"));
    }
    @Test void concurrentSameKeyProducesOneAssociationAndOneReceipt() throws Exception {
        var pool=Executors.newFixedThreadPool(2);var ready=new CountDownLatch(1);
        try{Callable<Long> call=()->{UserContext.setUserId(99801L);try{ready.await();return create("growth-race-key-00001");}finally{UserContext.clear();}};var a=pool.submit(call);var b=pool.submit(call);ready.countDown();assertEquals(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS));assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM post_growth_record",Integer.class));}
        finally{pool.shutdownNow();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS));}
    }
    @Test void imageRegistrationUsesRealDecoderRetainsHistoryAndRejectsVideoWithoutCapability() throws Exception {
        var records=sql.getMapper(cn.jualn.miniapp.module.media.mapper.MediaUploadRecordMapper.class);var attachments=sql.getMapper(cn.jualn.miniapp.module.media.mapper.MediaAttachmentMapper.class);var contexts=sql.getMapper(cn.jualn.miniapp.module.media.mapper.GrowthMediaMapper.class);
        var storage=mock(cn.jualn.miniapp.third.cos.service.CosService.class);var urls=new MediaDeliveryUrls(records);org.springframework.test.util.ReflectionTestUtils.setField(urls,"baseUrl","https://backend.example.test");
        var bytes=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",bytes);byte[] content=bytes.toByteArray();
        doAnswer(i->{java.nio.file.Files.write(i.getArgument(1),content);return null;}).when(storage).downloadObject(anyString(),any(),anyLong());
        when(storage.generateExactUploadCredential(any())).thenAnswer(i->cn.jualn.miniapp.third.cos.dto.CosUploadCredentialDTO.builder().bucket("private-test").region("local").objectKeys(i.getArgument(0)).tmpSecretId("test").tmpSecretKey("test").sessionToken("test").expiredTime(Instant.now().plusSeconds(600).getEpochSecond()).build());
        var lifecycle=new MediaUploadRecordService(records,storage);var files=new GrowthMediaService(contexts,records,attachments,lifecycle,urls,storage,manager);
        var scope=new cn.jualn.miniapp.module.media.bo.GrowthMediaBO.Scope(99801,"person-A","org-A","GROWTH",null);
        var declaration=new cn.jualn.miniapp.module.media.bo.GrowthMediaBO.Declaration("IMAGE","image.png","image/png",content.length);String key="growth-media-"+UUID.randomUUID();
        var allocation=files.upload(scope,List.of(declaration),()->{});assertNotNull(allocation.uploadId());
        var registered=files.register(scope,allocation.uploadId(),0,()->{});assertEquals("CHECKING",registered.safety());assertTrue(registered.unboundExpiresAt().isAfter(allocation.registrationExpiresAt()));assertEquals(registered.unboundExpiresAt(),files.register(scope,allocation.uploadId(),0,()->{}).unboundExpiresAt());assertTrue(registered.contentUrl().startsWith("https://backend.example.test/v1/media/files/"));assertTrue(files.reserved(allocation.credentials().objectKeys().get(0)));
        assertThrows(ContractProblemException.class,()->files.prepare(new cn.jualn.miniapp.module.media.bo.GrowthMediaBO.Scope(99802,"person-B","org-A","GROWTH",null),List.of(registered.mediaId())));
        long post=create("growth-media-bind-001");new TransactionTemplate(manager).executeWithoutResult(tx->files.bind(scope,post,List.of(registered.mediaId())));
        assertTrue(files.retainedFor(registered.mediaId(),post));db.update("DELETE FROM media_attachment WHERE target_type=1 AND target_id=?",post);
        new TransactionTemplate(manager).executeWithoutResult(tx->lifecycle.requestDeletion(records.selectById(registered.mediaId()).getObjectKey(),cn.jualn.miniapp.common.enums.TargetType.POST,post));assertEquals(1,records.selectById(registered.mediaId()).getStatus());
        var video=new cn.jualn.miniapp.module.media.bo.GrowthMediaBO.Declaration("VIDEO","video.mp4","video/mp4",100);
        assertEquals("/problems/growth-media-unavailable",assertThrows(ContractProblemException.class,()->files.upload(scope,List.of(video),()->{})).getType());
    }
    @Test void managementPredicatesApplyBeforeSearchPaginationAndCounts() {
        long id=create("growth-management-001");var content=sql.getMapper(AdminContentMapper.class);
        assertTrue(content.selectPage(null,null,"post",null,null,"latest",null,null,null,null,null,null,null,20,List.of("POST")).isEmpty());
        assertEquals(1,content.selectGrowthPage(null,null,"post",null,null,"latest",null,null,null,null,null,null,null,"ALL","org-A",List.of("org-A"),20,List.of("POST")).size());
        assertTrue(content.selectGrowthPage(null,null,"post",null,null,"latest",null,null,null,null,null,null,null,"ALL",null,List.of("org-B"),20,List.of("POST")).isEmpty());
        assertEquals(0,content.selectSummary().getPublished());
        db.update("INSERT INTO content_audit_log(target_type,target_id,audit_source,final_result,wx_result) VALUES(1,?,1,0,1)",id);long task=db.queryForObject("SELECT MAX(id) FROM content_audit_log",Long.class);
        db.update("INSERT INTO content_safety_review(task_id,target_type,target_id,submission_version,scene,resource_namespace,business_revision,snapshot_title,snapshot_content,snapshot_attachments,expected_checks,state,created_at,growth_organization_id) VALUES(?,1,?,'growth-submission','post','POST',0,'','accepted','[]',1,'pending',NOW(6),'org-A')",task,id);
        var reviews=sql.getMapper(ContentAuditLogMapper.class);
        assertEquals(0,reviews.countAdminReviews(null,null,1,"all",List.of("POST")));assertEquals(1,reviews.countGrowthReviews(null,null,1,"all",List.of("POST"),"ALL",null,List.of("org-A")));assertEquals(0,reviews.countGrowthReviews(null,null,1,"all",List.of("POST"),"ALL",null,List.of("org-B")));
        db.update("INSERT INTO report(reporter_id,target_type,target_id,reason,status) VALUES(99802,1,?,1,0)",id);var reports=sql.getMapper(ReportMapper.class);
        assertTrue(reports.selectAdminCasePage(null,null,"post",null,null,20).isEmpty());assertEquals(1,reports.selectGrowthCasePage(null,null,"post",null,null,20,"ALL",null,List.of("org-A")).size());assertTrue(reports.selectGrowthCasePage(null,null,"post",null,null,20,"ALL",null,List.of("org-B")).isEmpty());
    }
    @Test void editRechecksEligibilityAndVersionBeforeReplacingEffectiveContent() {
        long id=create("growth-edit-key-00001");var before=service.editable(id);
        var command=new GrowthRecordService.Edit(null,"edited",null,null,null,Set.of("content"));
        var after=service.edit(id,command,before.etag());assertEquals("edited",after.value().content());assertNotEquals(before.etag(),after.etag());
        assertEquals(412,assertThrows(ContractProblemException.class,()->service.edit(id,command,before.etag())).getStatus().value());
        doAnswer(i->{publishing=false;return null;}).when(safety).check(anyLong(),eq(id),anyString(),any());
        assertThrows(ContractProblemException.class,()->service.edit(id,new GrowthRecordService.Edit(null,"forbidden",null,null,null,Set.of("content")),after.etag()));assertEquals("edited",posts.selectById(id).getContent());
    }
    @Test void authorDeletionCannotBeRevivedByReceiptOrLateSafetyResult() {
        long id=create("growth-delete-key-01");db.update("UPDATE post SET deleted_at=NOW(6),status=4 WHERE id=?",id);
        assertThrows(ContractProblemException.class,()->create("growth-delete-key-01"));assertTrue(service.page(true,null,null,null,null,20).items().isEmpty());assertNotNull(links.find(id));
        var governance=new PostGovernanceService(authorization,posts,mock(cn.jualn.miniapp.infrastructure.cache.RedisService.class),mock(cn.jualn.miniapp.module.admin.operation.service.AdminAuditJournal.class),sql.getMapper(PostCommunityMapper.class),mock(PostCommunityNotifications.class),mock(org.springframework.beans.factory.ObjectProvider.class));
        assertThrows(RuntimeException.class,()->governance.applyContentSafety(id,0,"approved"));assertEquals(4,posts.includingDeleted(id).getStatus());
    }
    @Test void sharedPostAccessHasNoAnonymousExceptionAndRechecksMembershipAtCommit() {
        long id=create("growth-root-access-01");var shared=new PostPublicAccess(posts);var factory=new org.springframework.beans.factory.support.DefaultListableBeanFactory();factory.registerSingleton("growth",access);org.springframework.test.util.ReflectionTestUtils.setField(shared,"growth",factory.getBeanProvider(GrowthRecordAccess.class));
        assertFalse(shared.isPubliclyVisible(id));UserContext.clear();assertThrows(RuntimeException.class,()->shared.require(id,false));
        UserContext.setUserId(99802L);assertThrows(RuntimeException.class,()->shared.require(id,false));memberships.add("person-B/org-A");assertNotNull(shared.require(id,false));
        var transaction=new TransactionTemplate(manager);transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        assertThrows(RuntimeException.class,()->transaction.executeWithoutResult(tx->{shared.require(id,true);db.update("UPDATE post SET title='must roll back' WHERE id=?",id);memberships.remove("person-B/org-A");}));
        assertNull(posts.selectById(id).getTitle());verify(qualification).guard(99802);
    }
}
