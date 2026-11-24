package cn.jualn.miniapp.module.authorization.service;

import cn.jualn.miniapp.module.authorization.support.RbacTime;
import cn.jualn.miniapp.module.authorization.model.UnifiedModels;

import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real local HTTP/MySQL/Redis/session/governance and owners. WeChat/COS/safety are explicit doubles.
 * Synthetic READY publication exists only in this fixture; shipped catalogs remain non-effective. */
@org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="points.integration.jdbcUrl",matches="jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/points_integration_test_[a-z0-9]+")
class UnifiedR3IntegrationHttpTest extends PointsIntegrationHttpTest {
    @Override @BeforeAll void start() throws Exception {System.setProperty("unified.r3.fixture","EXPLICIT_SYNTHETIC_R3_APPROVAL");super.start();}
    @Override @AfterAll void stop() throws Exception {try {super.stop();} finally {System.clearProperty("unified.r3.fixture");}}
    @Test void allTwentyOneNewDutiesReachTheirRealReadOwnerAndOldTokenFailsAfterRevocation() throws Exception {
        long staff=930120;syntheticUser(staff,true);String org=organization("R3 read scope");person(USER,List.of(org));
        db.update("INSERT INTO activity(id,user_id,title) VALUES(993001,?,'R3 registration fixture')",ROOT);
        var paths=new LinkedHashMap<String,String>();
        paths.put("ARTICLE_CATEGORY_MAINTAIN","/v1/admin/article-categories");paths.put("POST_DICTIONARY_MAINTAIN","/v1/admin/topics");
        paths.put("POST_MODERATE","/v1/admin/contents?type=post");
        paths.put("OFFICIAL_CONTENT_EDIT","/v1/admin/official-contents");paths.put("OFFICIAL_CONTENT_PUBLISH","/v1/admin/official-contents");
        paths.put("ACTIVITY_MANAGE","/v1/admin/activities");paths.put("PUBLIC_EVENT_MANAGE","/v1/admin/public-events");
        paths.put("ACTIVITY_REGISTRATION_READ","/v1/admin/activities/993001/registrations");paths.put("ACTIVITY_REGISTRATION_EXPORT","/v1/admin/activities/993001/registrations");
        paths.put("PERSON_IDENTITY_READ",PEOPLE+USER+"/identity");paths.put("ORG_MEMBER_READ","/v1/admin/organizations/"+org+"/members");paths.put("PERSON_ORGANIZATION_READ",PEOPLE+USER+"/memberships");
        paths.put("USER_ACCOUNT_GOVERN","/v1/admin/users");paths.put("REPORT_HANDLE","/v1/admin/report-cases");paths.put("NOTICE_MONITOR","/v1/admin/notification-plans");paths.put("SYSTEM_MONITOR","/v1/admin/system/overview");
        paths.put("POST_SAFETY_REVIEW","/v1/admin/reviews");paths.put("ACTIVITY_SAFETY_REVIEW","/v1/admin/reviews");paths.put("PUBLIC_EVENT_COMMENT_REVIEW","/v1/admin/reviews");
        paths.put("ACTIVITY_COMMENT_MODERATE","/v1/admin/contents?type=comment");paths.put("PUBLIC_EVENT_COMMENT_MODERATE","/v1/admin/contents?type=comment");
        assertEquals(21,paths.size());
        String token=null;
        for(var item:paths.entrySet()) {
            var target=item.getKey().equals("ORG_MEMBER_READ")?new UnifiedModels.Target("ORGANIZATION",null,org):
                Set.of("PERSON_IDENTITY_READ","PERSON_ORGANIZATION_READ").contains(item.getKey())?new UnifiedModels.Target("PERSON",Long.toString(USER),null):new UnifiedModels.Target("PLATFORM",null,null);
            var receipt=duty(staff,item.getKey(),target);if(token==null)token=qr(staff);
            assertEquals(1,db.queryForObject("SELECT role FROM user_profile WHERE id=?",Integer.class,staff));
            expect(200,"GET",item.getValue(),token,null);
            expect(403,"GET","/v1/admin/authorization/users/"+ROOT+"/role-assignments",token,null);
            revokeDuty(receipt.scopeGrantIds().get(0));
            var ended=call("GET",item.getValue(),token,null,Map.of());assertTrue(Set.of(401,403).contains(ended.statusCode()),item.getKey()+" "+ended.body());
        }
    }
    @Test void narrowDictionariesCannotOperatePostsOrArticlesAndKeepStrongConditions() throws Exception {
        long category=930121,dictionary=930122;syntheticUser(category,true);syntheticUser(dictionary,true);
        duty(category,"ARTICLE_CATEGORY_MAINTAIN",new UnifiedModels.Target("PLATFORM",null,null));String c=qr(category);
        var created=call("POST","/v1/admin/article-categories",c,Map.of("name","R3 narrow category","displayOrder",1,"enabled",true),Map.of());var body=checked(201,created);
        String path="/v1/admin/article-categories/"+body.path("id").asText(),tag=created.headers().firstValue("ETag").orElseThrow();
        expect(428,"PUT",path,c,Map.of("name","R3 category edited","displayOrder",2,"enabled",false));
        checked(200,call("PUT",path,c,Map.of("name","R3 category edited","displayOrder",2,"enabled",false),Map.of("If-Match",tag)));
        checked(412,call("PUT",path,c,Map.of("name","stale","displayOrder",3,"enabled",true),Map.of("If-Match",tag)));
        expect(403,"GET","/v1/admin/articles",c,null);expect(403,"GET","/v1/admin/article-publishers",c,null);
        duty(dictionary,"POST_DICTIONARY_MAINTAIN",new UnifiedModels.Target("PLATFORM",null,null));String d=qr(dictionary);
        var topic=checked(201,call("POST","/v1/admin/topics",d,Map.of("code","R3_NARROW_TOPIC","name","R3 narrow topic"),Map.of()));assertFalse(topic.path("id").asText().isBlank());
        expect(403,"GET","/v1/admin/contents?type=post",d,null);expect(403,"GET","/v1/admin/reviews",d,null);
    }
    @Test void publisherOnlyUsesStatusCommandsWithoutBodyEditAndStaleReplayFails() throws Exception {
        long editor=930123,publisher=930124;syntheticUser(editor,true);syntheticUser(publisher,true);
        duty(editor,"OFFICIAL_CONTENT_EDIT",new UnifiedModels.Target("PLATFORM",null,null));var publicationDuty=duty(publisher,"OFFICIAL_CONTENT_PUBLISH",new UnifiedModels.Target("PLATFORM",null,null));
        String e=qr(editor),p=qr(publisher);var save=Map.of("title","R3 publication","summary","summary","body","saved body","publisher","saved signer","category","NEWS","publishStatus","DRAFT","imageAttachmentIds",List.of());
        var draft=call("POST","/v1/admin/official-contents",e,save,Map.of());var value=checked(201,draft);String path="/v1/admin/official-contents/"+value.path("id").asText(),tag=draft.headers().firstValue("ETag").orElseThrow();
        expect(403,"PUT",path,p,save);checked(409,call("POST",path+":unpublish",p,null,Map.of("If-Match",tag)));expect(428,"POST",path+":publish",p,null);
        var published=call("POST",path+":publish",p,null,Map.of("If-Match",tag));var publicBody=checked(200,published);String fresh=published.headers().firstValue("ETag").orElseThrow();
        assertEquals("saved body",publicBody.path("body").asText());assertEquals("saved signer",publicBody.path("publisher").asText());assertEquals("PUBLISHED",publicBody.path("publishStatus").asText());
        checked(412,call("POST",path+":publish",p,null,Map.of("If-Match",tag)));
        assertEquals(publicBody,checked(200,call("POST",path+":publish",p,null,Map.of("If-Match",fresh))));
        var withdrawn=checked(200,call("POST",path+":unpublish",p,null,Map.of("If-Match",fresh)));assertEquals("UNPUBLISHED",withdrawn.path("publishStatus").asText());assertEquals(publicBody.path("publishedAt"),withdrawn.path("publishedAt"));
        String withdrawnTag="\"official-content-"+value.path("id").asText()+"-v3\"";
        OFFICIAL_SAFETY_HOOK.set(()->{throw cn.jualn.miniapp.module.officialcontent.service.OfficialContentSafetyChecks.unavailable();});
        try {checked(503,call("POST",path+":publish",p,null,Map.of("If-Match",withdrawnTag)));} finally {OFFICIAL_SAFETY_HOOK.set(null);}
        OFFICIAL_SAFETY_HOOK.set(()->revokeDuty(publicationDuty.scopeGrantIds().get(0)));
        try {checked(403,call("POST",path+":publish",p,null,Map.of("If-Match",withdrawnTag)));} finally {OFFICIAL_SAFETY_HOOK.set(null);}
        assertEquals("UNPUBLISHED",db.queryForObject("SELECT publish_status FROM official_content WHERE id=?",String.class,Long.parseLong(value.path("id").asText())));
        assertEquals(3L,db.queryForObject("SELECT revision FROM official_content WHERE id=?",Long.class,Long.parseLong(value.path("id").asText())));
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM admin_operation_log WHERE target_type='official-content' AND target_id=?",Integer.class,Long.parseLong(value.path("id").asText())));
    }
    @Test void expiryAtFinalCommitRollsBackPublicationAndAuditInTheSameOwnerTransaction() throws Exception {
        long actor=930125;syntheticUser(actor,true);
        var draft=call("POST","/v1/admin/official-contents",root,Map.of("title","expiry candidate","summary","","body","saved body","publisher","saved signer","category","NEWS","publishStatus","DRAFT","imageAttachmentIds",List.of()),Map.of());
        var value=checked(201,draft);long id=Long.parseLong(value.path("id").asText());String tag=draft.headers().firstValue("ETag").orElseThrow();
        String rootAssignment=app.getBean(RbacAuthorizationReader.class).read(ROOT).grants().stream().filter(g->"ROOT".equals(g.roleCode())).findFirst().orElseThrow().assignmentId();
        var selection=new UnifiedModels.Selection("OFFICIAL_CONTENT_PUBLISH",new UnifiedModels.Target("PLATFORM",null,null),RbacTime.wire(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).plusSeconds(4).withNano(0)),null);
        var intent=new UnifiedModels.Intent("GRANT",Long.toString(actor),List.of(selection),rootAssignment,null,null,"explicit short-lived isolated publication source");
        var unified=app.getBean(UnifiedAuthorizationService.class);var preview=unified.preview(ROOT,intent,UUID.randomUUID().toString());unified.commit(ROOT,new UnifiedModels.Commit(preview.previewId(),intent),UUID.randomUUID().toString());
        var tx=new org.springframework.transaction.support.TransactionTemplate(app.getBean(org.springframework.transaction.PlatformTransactionManager.class));tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        assertThrows(cn.jualn.miniapp.common.exception.BusinessException.class,()->tx.execute(status->{
            app.getBean(cn.jualn.miniapp.module.officialcontent.service.OfficialPublicationWriter.class).commit(actor,id,true,tag,1);
            try {Thread.sleep(4100);} catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
            return null;
        }));
        assertEquals("DRAFT",db.queryForObject("SELECT publish_status FROM official_content WHERE id=?",String.class,id));
        assertEquals(1L,db.queryForObject("SELECT revision FROM official_content WHERE id=?",Long.class,id));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM admin_operation_log WHERE target_type='official-content' AND target_id=?",Integer.class,id));
    }

    @Test void platformDelegationRequiresIndependentApprovalAndPreservesAnIndependentReplacementSource() throws Exception {
        long delegate=930126,staff=930127;syntheticUser(delegate,true);syntheticUser(staff,true);
        String prefix="/v1/admin/authorization",expiry=RbacTime.wire(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).plusDays(1).withNano(0));
        var selection=new UnifiedModels.Selection("ARTICLE_CATEGORY_MAINTAIN",new UnifiedModels.Target("PLATFORM",null,null),expiry,null);
        write(403,"POST",prefix+"/delegation-authorities",root,new UnifiedModels.AuthorityRequest(Long.toString(delegate),List.of(selection),"SAME_ORGANIZATION_MEMBERS",expiry,"not a membership-derived platform authority"),Map.of());
        var authority=write(200,"POST",prefix+"/delegation-authorities",root,new UnifiedModels.AuthorityRequest(Long.toString(delegate),List.of(selection),"INDEPENDENT_APPROVAL",expiry,"explicit platform approval"),Map.of());String d=qr(delegate);
        var invalid=new UnifiedModels.Intent("GRANT",Long.toString(staff),List.of(new UnifiedModels.Selection(selection.dutyCode(),new UnifiedModels.Target("ORGANIZATION",null,"wrong-org"),expiry,null)),authority.path("authorityId").asText(),null,null,"wrong business target");
        write(400,"POST",prefix+"/duty-previews",d,invalid,Map.of());
        var self=new UnifiedModels.Intent("GRANT",Long.toString(delegate),List.of(selection),authority.path("authorityId").asText(),null,null,"self grant forbidden");write(403,"POST",prefix+"/duty-previews",d,self,Map.of());
        var intent=new UnifiedModels.Intent("GRANT",Long.toString(staff),List.of(selection),authority.path("authorityId").asText(),null,null,"delegated category appointment");
        var preview=write(200,"POST",prefix+"/duty-previews",d,intent,Map.of());write(200,"POST",prefix+"/duty-changes",d,new UnifiedModels.Commit(preview.path("previewId").asText(),intent),Map.of());
        String token=qr(staff);expect(200,"GET","/v1/admin/article-categories",token,null);
        var replacement=duty(staff,selection.dutyCode(),selection.target());
        var revoke=new UnifiedModels.Intent("REVOKE",null,null,null,"DELEGATION_AUTHORITY",authority.path("authorityId").asText(),"emergency branch withdrawal");
        var impact=write(200,"POST",prefix+"/duty-previews",root,revoke,Map.of());
        assertTrue(impact.toString().contains("BECOMES_INEFFECTIVE"));assertTrue(impact.toString().contains("REMAINS_EFFECTIVE"));
        write(200,"POST",prefix+"/duty-changes",root,new UnifiedModels.Commit(impact.path("previewId").asText(),revoke),Map.of());
        expect(200,"GET","/v1/admin/article-categories",token,null);assertEquals(0,expect(200,"GET",prefix+"/me/duty-options",d,null).path("items").size());
        revokeDuty(replacement.scopeGrantIds().get(0));assertTrue(Set.of(401,403).contains(call("GET","/v1/admin/article-categories",token,null,Map.of()).statusCode()));
        String assignments=prefix+"/users/"+staff+"/role-assignments";var version=expect(200,"GET",assignments,root,null).path("authorizationVersion").asText();
        write(403,"POST",assignments,root,Map.of("roleCode","ARTICLE_CATEGORY_STEWARD_V1","namespace","ARTICLE","scope",Map.of("type","GLOBAL"),"reason","cannot bypass platform via Standalone","expectedAuthorizationVersion",version),Map.of());
    }

}
