package cn.jualn.miniapp.module.person;

import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.common.security.AdminStpUtil;
import cn.jualn.miniapp.module.authorization.model.UnifiedModels.*;
import cn.jualn.miniapp.module.authorization.model.RbacModels;
import cn.jualn.miniapp.module.authorization.service.*;
import cn.jualn.miniapp.module.authorization.mapper.RbacMapper;
import cn.jualn.miniapp.module.authorization.support.RbacTime;
import cn.jualn.miniapp.module.activity.service.*;
import cn.jualn.miniapp.module.activity.mapper.*;
import cn.jualn.miniapp.module.activity.bo.*;
import cn.jualn.miniapp.module.activity.service.impl.ActivityRegistrationServiceImpl;
import cn.jualn.miniapp.module.timeline.service.TimelineService;
import cn.jualn.miniapp.module.timeline.bo.TimelineFactBO;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Fresh identified local schema. Real MySQL, migrations, authorization and transactions.
 * Session identity and Timeline inputs are explicit adapters; no WeChat/provider claim. */
class UnifiedFinalDatabaseTest extends ManagedPersonDatabaseHttpTest {
    RbacAuthorizationReader reader(){return context.getBean(RbacAuthorizationReader.class);}
    UnifiedAuthorizationService unified(){return context.getBean(UnifiedAuthorizationService.class);}
    String root(){return context.getBean(RbacMapper.class).assignments(1).stream().filter(a->"ROOT".equals(a.getRoleCode())).findFirst().orElseThrow().getId();}
    String expiry(){return RbacTime.wire(LocalDateTime.now(ZoneOffset.UTC).plusDays(1).toLocalDate().atStartOfDay());}
    void publish(String code){
        var t=context.getBean(UnifiedDutyCatalog.class).template(code);
        db.update("UPDATE rbac_role SET lifecycle='ENABLED',readiness='READY',grantable=1,unavailable_reason=NULL WHERE code=?",t.roleCode());
        for(String p:db.queryForList("SELECT permission_code FROM rbac_role_permission WHERE role_code=?",String.class,t.roleCode()))db.update("UPDATE rbac_permission_definition SET lifecycle='ENABLED',readiness='READY',grantable=1,unavailable_reason=NULL WHERE code=?",p);
    }
    Receipt grant(long actor,long user,String authority,String code,Target target){
        var i=new Intent("GRANT",Long.toString(user),List.of(new Selection(code,target,expiry(),null)),authority,null,null,"isolated explicit appointment");
        var p=unified().preview(actor,i,key());return unified().commit(actor,new Commit(p.previewId(),i),key());
    }
    void revoke(String type,String id){var i=new Intent("REVOKE",null,null,null,type,id,"isolated withdrawal");var p=unified().preview(1,i,key());unified().commit(1,new Commit(p.previewId(),i),key());}
    @Test void unifiedFinalCatalogOrdinaryAccountsAndPersonReadiness() {
        assertEquals(66,count("rbac_permission_definition"));assertEquals(27,count("rbac_role"));
        assertEquals(36,context.getBean(UnifiedDutyCatalog.class).templates().size());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM rbac_role_permission WHERE role_code IN ('REGISTRATION_READER','REGISTRATION_EXPORTER','EVENT_OPERATOR') AND permission_code='activity:registration:dispose'",Integer.class));
        long user=user();assertFalse(reader().read(user).canUseAdmin());assertTrue(reader().read(user).grants().isEmpty());
        for(int scalar:List.of(1,2,3)){db.update("UPDATE user_profile SET role=? WHERE id=?",scalar,user);assertFalse(reader().read(user).canUseAdmin());}
        db.update("UPDATE rbac_permission_definition SET readiness='NOT_READY',grantable=0,unavailable_reason='PROVIDER_NOT_VERIFIED' WHERE code='identity:read'");
        assertFalse(reader().read(1).allows("identity:read","IDENTITY"));
        var tx=new TransactionTemplate(context.getBean(DataSourceTransactionManager.class));long fresh=90001;
        db.update("INSERT INTO user_profile(id,openid) VALUES(?,'new-account-final')",fresh);
        tx.executeWithoutResult(s->context.getBean(AuthorizationAuthorityService.class).initializeNewUser(fresh));
        assertEquals("ASSIGNMENT",context.getBean(RbacMapper.class).subject(fresh).getSource());assertFalse(reader().read(fresh).canUseAdmin());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM rbac_user_role_assignment WHERE role_code='ROOT' AND revoked_at IS NULL",Integer.class));
    }
    @Test void unifiedFinalDelegationAdmissionCandidatesAndIndependentSources() throws Exception {
        publish("MENTOR_MANAGE");long delegate=user(),staff=user();
        var selection=new Selection("MENTOR_MANAGE",new Target("PLATFORM",null,null),expiry(),null);
        var a=unified().grantAuthority(1,new AuthorityRequest(Long.toString(delegate),List.of(selection),"INDEPENDENT_APPROVAL",expiry(),"isolated independent authority"),key());
        assertTrue(reader().read(delegate).canUseAdmin());assertTrue(reader().read(delegate).grants().isEmpty());
        var targets=unified().targetOptions(delegate,a.authorityId(),"MENTOR_MANAGE",null,null,20,null);
        var targetWire=wire("GET","/v1/admin/authorization/me/target-options?authorityId="+root()+"&dutyCode=MENTOR_MANAGE",null,null,null,"application/json",200);
        assertEquals("PLATFORM",targetWire.body().path("items").get(0).path("target").path("type").asText());
        wire("GET","/v1/admin/authorization/me/target-options?authorityId="+root()+"&dutyCode=MENTOR_MANAGE&unexpected=x",null,null,null,"application/json",400);
        wire("GET","/v1/admin/authorization/me/recipient-options?authorityId="+root()+"&dutyCode=MENTOR_MANAGE&targetType=PLATFORM&pageSize=1",null,null,null,"application/json",200);
        db.update("UPDATE rbac_permission_definition SET readiness='NOT_READY',grantable=0,unavailable_reason='PROVIDER_NOT_VERIFIED' WHERE code='mentor:qualification-manage'");
        var unavailable=wire("GET","/v1/admin/authorization/me/target-options?authorityId="+root()+"&dutyCode=MENTOR_MANAGE",null,null,null,"application/json",503);
        assertEquals("/problems/unified-authorization-unavailable",unavailable.body().path("type").asText());publish("MENTOR_MANAGE");
        assertEquals(1,targets.items().size());assertEquals("PLATFORM",targets.items().get(0).target().type());
        var first=unified().recipientOptions(delegate,a.authorityId(),"MENTOR_MANAGE",selection.target(),null,null,1,null);
        assertEquals(1,first.items().size());assertNotNull(first.nextCursor());
        assertFalse(reader().read(delegate).allows("user:read","USER"));
        var p=create("Unbound candidate name");
        var named=unified().recipientOptions(delegate,a.authorityId(),"MENTOR_MANAGE",selection.target(),null,"Unbound candidate name",20,null);
        assertEquals("PERSON_UNBOUND",named.items().get(0).bindingState());assertNull(named.items().get(0).userId());assertFalse(named.items().get(0).selectable());
        assertThrows(ContractProblemException.class,()->unified().recipientOptions(delegate,a.authorityId(),"MENTOR_MANAGE",selection.target(),null,null,1,first.nextCursor()));
        var freshPage=unified().recipientOptions(delegate,a.authorityId(),"MENTOR_MANAGE",selection.target(),null,null,1,null);
        db.update("UPDATE user_profile SET nickname='changed candidate',profile_revision=profile_revision+1 WHERE id=?",staff);
        assertEquals(400,assertThrows(ContractProblemException.class,()->unified().recipientOptions(delegate,a.authorityId(),"MENTOR_MANAGE",selection.target(),null,null,1,freshPage.nextCursor())).getStatus().value());
        var granted=grant(delegate,staff,a.authorityId(),"MENTOR_MANAGE",selection.target());
        assertTrue(reader().read(staff).allowsWork("MENTOR_MANAGE","MENTOR","mentor:qualification-manage"));
        var ownPerson=create("self approval must fail");bindings.createUserPersonBinding(1,bind(ownPerson,staff),key());
        assertEquals(403,assertThrows(ContractProblemException.class,()->context.getBean(cn.jualn.miniapp.module.person.service.qualification.MentorManagementService.class)
                .put(staff,ownPerson.value().personId(),new cn.jualn.miniapp.module.person.bo.QualificationBO.ProfileWrite("position","unit","checked","synthetic"),key(),null,"*")).getStatus().value());
        var independent=grant(1,staff,root(),"MENTOR_MANAGE",selection.target());
        revoke("DELEGATION_AUTHORITY",a.authorityId());
        assertFalse(reader().read(delegate).canUseAdmin());assertTrue(reader().read(staff).allowsWork("MENTOR_MANAGE","MENTOR","mentor:qualification-manage"));
        assertThrows(ContractProblemException.class,()->unified().targetOptions(delegate,a.authorityId(),"MENTOR_MANAGE",null,null,20,null));
        revoke("SCOPE",independent.scopeGrantIds().get(0));assertFalse(reader().read(staff).canUseAdmin());
    }
    @Test void unifiedFinalRegistrationInvalidationArbitratesVersionsAndCannotBeRestored() throws Exception {
        publish("ACTIVITY_REGISTRATION_DISPOSE");publish("ACTIVITY_REGISTRATION_READ");
        long disposer=user(),observer=user(),applicant=user();Target platform=new Target("PLATFORM",null,null);
        grant(1,disposer,root(),"ACTIVITY_REGISTRATION_DISPOSE",platform);grant(1,observer,root(),"ACTIVITY_REGISTRATION_READ",platform);
        var factory=new MybatisSqlSessionFactoryBean();factory.setDataSource(context.getBean(javax.sql.DataSource.class));
        var config=new MybatisConfiguration();config.setMapUnderscoreToCamelCase(true);config.setCacheEnabled(false);factory.setConfiguration(config);
        factory.setMapperLocations(new ClassPathResource("mapper/ActivityMapper.xml"),new ClassPathResource("mapper/ActivityRegistrationMapper.xml"));
        var session=new SqlSessionTemplate(factory.getObject());var activities=session.getMapper(ActivityMapper.class);var registrations=session.getMapper(ActivityRegistrationMapper.class);
        var timeline=mock(TimelineService.class);var end=new TimelineFactBO();end.setNodeType("REGISTRATION_END");end.setStartPrecision(2);end.setStartTime(LocalDateTime.now(ZoneId.of("Asia/Shanghai")).plusDays(1));
        var main=new TimelineFactBO();main.setNodeType("ACTIVITY_START");main.setStartPrecision(2);main.setStartTime(LocalDateTime.now(ZoneId.of("Asia/Shanghai")).plusDays(2));
        when(timeline.listTimelinesByTarget(any(),anyLong())).thenReturn(List.of(end,main));
        var current=new CurrentAuthorization(reader());org.springframework.test.util.ReflectionTestUtils.setField(current,"lease",new BusinessAuthorizationLease(context.getBean(TransactionalAuthorization.class),reader()));
        var target=new ActivityRegistrationServiceImpl(current,activities,registrations,new ActivityParticipationPolicy(),timeline,mock(ActivityPublicHistory.class),mock(ActivityReminderCatchUp.class));
        var proxy=new org.springframework.aop.framework.ProxyFactory(target);proxy.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(context.getBean(DataSourceTransactionManager.class),new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        var service=(ActivityRegistrationService)proxy.getProxy();
        db.update("INSERT INTO activity(user_id,title,summary,registration_mode,participant_mode,publish_status,lifecycle_status,form_schema,form_version,capacity,capacity_unit) VALUES(?,'final registration','test',2,1,1,0,?,'f1',1,1)",applicant,"{\"fields\":[{\"fieldKey\":\"student\",\"purpose\":\"NAME\",\"displayOrder\":0,\"label\":\"Name\",\"type\":\"text\",\"required\":true,\"maxLength\":120}],\"allowModification\":true}");
        long activity=db.queryForObject("SELECT MAX(id) FROM activity",Long.class);
        db.update("INSERT INTO activity_registration(activity_id,user_id,form_data,status,form_version,submitted_at,created_at,updated_at) VALUES(?,?,'{\"student\":\"original\"}',1,'f1',NOW(),NOW(),NOW())",activity,applicant);
        long id=db.queryForObject("SELECT MAX(id) FROM activity_registration",Long.class);var initial=new RegistrationVersionBO(id,1);
        try {
            sessionActor(observer);assertThrows(cn.jualn.miniapp.common.exception.BusinessException.class,()->service.invalidateContract(activity,id,"denied",initial));
            sessionActor(disposer);
            assertEquals(404,assertThrows(cn.jualn.miniapp.common.exception.BusinessException.class,()->service.invalidateContract(activity+99999,id,"wrong activity",initial)).getResultCode().getCode());
            assertEquals(428,assertThrows(ContractProblemException.class,()->service.invalidateContract(activity,id,"required",null)).getStatus().value());
            var results=race(()->{sessionActor(disposer);return service.invalidateContract(activity,id,"first A",initial);},()->{sessionActor(disposer);return service.invalidateContract(activity,id,"first B",initial);});
            assertEquals(1,results.stream().filter(ActivityRegistrationBO.class::isInstance).count(),results.toString());
            assertEquals(1,results.stream().filter(x->x instanceof ContractProblemException e && e.getStatus().value()==412).count());
            sessionActor(disposer);var row=registrations.selectById(id);assertEquals(2,row.getStatus());assertEquals(2,row.getContractVersion());
            String firstReason=row.getInvalidReason();var currentVersion=new RegistrationVersionBO(id,2);
            assertEquals(firstReason,service.invalidateContract(activity,id,"must not replace",currentVersion).getInvalidReason());
            assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM activity_registration WHERE activity_id=? AND status=1",Integer.class,activity));
            UserContext.setUserId(applicant);
            var rejected=assertThrows(ContractProblemException.class,()->service.submitContract(activity,"f1",ActivityFormPolicy.parse("[{\"fieldKey\":\"student\",\"value\":\"original\"}]"),currentVersion));
            assertEquals("/problems/registration-invalidated",rejected.getType());
            assertEquals(0,registrations.restore(id,"{}",LocalDateTime.now()));
            assertEquals(0,registrations.restoreVersioned(id,2,"{}","f1",LocalDateTime.now()));
            assertEquals(firstReason,registrations.selectById(id).getInvalidReason());
            long other=user();db.update("INSERT INTO activity_registration(activity_id,user_id,form_data,status,form_version,submitted_at,created_at,updated_at) VALUES(?,?,'{\"student\":\"other\"}',1,'f1',NOW(),NOW(),NOW())",activity,other);
            long competing=db.queryForObject("SELECT MAX(id) FROM activity_registration",Long.class);var observed=new RegistrationVersionBO(competing,1);
            var competition=race(()->{sessionActor(disposer);return service.invalidateContract(activity,competing,"administrative race",observed);},()->{UserContext.setUserId(other);try{return service.cancelMineContract(activity,observed);}finally{UserContext.clear();}});
            assertEquals(1,competition.stream().filter(ActivityRegistrationBO.class::isInstance).count(),competition.toString());
            assertEquals(1,competition.stream().filter(x->x instanceof ContractProblemException e && e.getStatus().value()==412).count());
            assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM activity_registration WHERE activity_id=? AND status=1",Integer.class,activity));
            long unknownUser=user();db.update("INSERT INTO activity_registration(activity_id,user_id,form_data,status,form_version,submitted_at,created_at,updated_at) VALUES(?,?,'{}',1,'f1',NOW(),NOW(),NOW())",activity,unknownUser);
            long unknown=db.queryForObject("SELECT MAX(id) FROM activity_registration",Long.class);
            when(timeline.listTimelinesByTarget(any(),anyLong())).thenReturn(List.of(end));sessionActor(disposer);
            assertEquals(409,assertThrows(ContractProblemException.class,()->service.invalidateContract(activity,unknown,"unknown main time",new RegistrationVersionBO(unknown,1))).getStatus().value());
            main.setStartTime(LocalDateTime.now(ZoneId.of("Asia/Shanghai")).minusHours(1));when(timeline.listTimelinesByTarget(any(),anyLong())).thenReturn(List.of(end,main));
            assertEquals(409,assertThrows(ContractProblemException.class,()->service.invalidateContract(activity,unknown,"already started",new RegistrationVersionBO(unknown,1))).getStatus().value());
            assertEquals(1,registrations.selectById(unknown).getContractVersion());
        } finally {UserContext.clear();cn.dev33.satoken.SaManager.getSaTokenContext().clearContext();}
    }
    @Test void unifiedFinalUpgradePreservesHistoryAndRegistrationFacts() {
        String url=System.getProperty("managed.person.test.jdbcUrl"),upgrade=url+"_upgrade",schema=upgrade.substring(upgrade.lastIndexOf('/')+1);
        var admin=new org.springframework.jdbc.core.JdbcTemplate(source(url.substring(0,url.lastIndexOf('/'))+"/mysql"));
        assertEquals(0,admin.queryForObject("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",Integer.class,schema));
        admin.execute("CREATE DATABASE "+schema);
        var ds=source(upgrade);org.flywaydb.core.Flyway.configure().dataSource(ds).target("90").load().migrate();
        var old=new org.springframework.jdbc.core.JdbcTemplate(ds);
        old.update("INSERT INTO user_profile(id,openid) VALUES(1,'upgrade-owner')");
        old.update("INSERT INTO rbac_authorization_authority(user_id,source,version,updated_at) VALUES(1,'ASSIGNMENT',?,UTC_TIMESTAMP(6))",key());
        old.update("INSERT INTO rbac_user_role_assignment(id,user_id,role_code,namespace,scope_type,granted_at,version,grant_change_id) VALUES(?,1,'ROOT','AUTHORIZATION','GLOBAL',UTC_TIMESTAMP(6),?,?)",key(),key(),key());
        old.update("INSERT INTO activity(id,user_id,title,summary) VALUES(1,1,'upgrade history','preserve')");
        old.update("INSERT INTO activity_registration(activity_id,user_id,form_data,status,submitted_at,invalidated_at,invalidated_by,invalid_reason) VALUES(1,1,'{}',3,NOW(),NOW(),1,'retained reason')");
        var before=old.queryForList("SELECT * FROM rbac_user_role_assignment");
        var flyway=org.flywaydb.core.Flyway.configure().dataSource(ds).target("91").load();assertEquals(1,flyway.migrate().migrationsExecuted);flyway.validate();assertEquals(0,flyway.migrate().migrationsExecuted);
        assertEquals(before,old.queryForList("SELECT * FROM rbac_user_role_assignment"));
        assertEquals(3,old.queryForObject("SELECT status FROM activity_registration",Integer.class));assertEquals("retained reason",old.queryForObject("SELECT invalid_reason FROM activity_registration",String.class));
        assertEquals("NOT_READY",old.queryForObject("SELECT readiness FROM rbac_role WHERE code='REGISTRATION_DISPOSER_V1'",String.class));
        assertEquals(500,old.queryForObject("SELECT CHARACTER_MAXIMUM_LENGTH FROM information_schema.columns WHERE table_schema=? AND table_name='activity_registration' AND column_name='invalid_reason'",Integer.class,schema));
        assertEquals(1,old.queryForObject("SELECT COUNT(DISTINCT catalog_digest) FROM rbac_permission_definition",Integer.class));
    }

    @Test void unifiedFinalGrowthOwnerProjectionUsesRealCommentParentAndScopedSummary() throws Exception {
        publish("POST_MODERATE");publish("POST_SAFETY_REVIEW");long staff=user();grant(1,staff,root(),"POST_MODERATE",new Target("PLATFORM",null,null));
        var factory=new MybatisSqlSessionFactoryBean();factory.setDataSource(context.getBean(javax.sql.DataSource.class));var config=new MybatisConfiguration();config.setMapUnderscoreToCamelCase(true);config.setCacheEnabled(false);config.addMapper(cn.jualn.miniapp.module.post.mapper.GrowthRecordMapper.class);factory.setConfiguration(config);
        factory.setMapperLocations(new ClassPathResource("mapper/GrowthContentMapper.xml"),new ClassPathResource("mapper/AdminContentMapper.xml"),new ClassPathResource("mapper/ContentAuditLogMapper.xml"));
        var session=new SqlSessionTemplate(factory.getObject());var links=session.getMapper(cn.jualn.miniapp.module.post.mapper.GrowthRecordMapper.class);
        var access=new cn.jualn.miniapp.module.post.service.GrowthRecordAccess(links,mock(cn.jualn.miniapp.module.post.mapper.PostMapper.class),mock(cn.jualn.miniapp.module.post.service.GrowthQualification.class),new CurrentAuthorization(reader()),mock(cn.jualn.miniapp.module.user.service.UserAccountService.class));
        var resolver=new cn.jualn.miniapp.module.content.service.GrowthManagementTargets(session.getMapper(cn.jualn.miniapp.module.content.mapper.GrowthContentMapper.class),access);
        db.update("INSERT INTO post(user_id,title,content,status,post_type) VALUES(1,'growth','test',2,'NORMAL')");long post=db.queryForObject("SELECT MAX(id) FROM post",Long.class);
        db.update("INSERT INTO post_growth_record(post_id,person_id,organization_id,record_type) VALUES(?,'retained-person','fixed-growth-org','GROWTH')",post);
        db.update("INSERT INTO comment(user_id,content,target_type,target_id,status) VALUES(1,'comment',1,?,1)",post);long comment=db.queryForObject("SELECT MAX(id) FROM comment",Long.class);
        var target=resolver.location("comment",comment);assertEquals("POST",target.namespace());assertEquals("ORGANIZATION",target.businessTarget().type());assertEquals("fixed-growth-org",target.businessTarget().organizationId());
        assertTrue(access.allowedAdmin(post,1,"POST_MODERATE","content:read","post:dispose"));assertFalse(access.allowedAdmin(post,staff,"POST_MODERATE","content:read","post:dispose"));
        assertTrue(access.organizations(1,"POST_MODERATE","content:read").contains("fixed-growth-org"));assertTrue(access.organizations(staff,"POST_MODERATE","content:read").isEmpty());
        db.update("INSERT INTO activity(user_id,title,summary) VALUES(1,'parent','test')");long activity=db.queryForObject("SELECT MAX(id) FROM activity",Long.class);
        db.update("INSERT INTO comment(user_id,content,target_type,target_id,status) VALUES(1,'activity comment',2,?,1)",activity);long activityComment=db.queryForObject("SELECT MAX(id) FROM comment",Long.class);
        var ordinary=resolver.location("comment",activityComment);assertEquals("ACTIVITY",ordinary.namespace());assertEquals("PLATFORM",ordinary.businessTarget().type());
        var summaries=session.getMapper(cn.jualn.miniapp.module.content.mapper.AdminContentMapper.class);
        assertEquals(2,summaries.selectGrowthSummary(null,"ALL",null,List.of("fixed-growth-org")).getPublished());
        assertEquals(0,summaries.selectGrowthSummary(null,"ALL",null,List.of("another-org")).getPublished());
        assertNotNull(session.getMapper(cn.jualn.miniapp.module.audit.mapper.ContentAuditLogMapper.class).selectGrowthReviewSummary(List.of("POST"),"ALL",null,List.of("fixed-growth-org")));
        db.update("UPDATE rbac_permission_definition SET readiness='NOT_READY',grantable=0,unavailable_reason='PROVIDER_NOT_VERIFIED' WHERE code='post:dispose'");
        assertFalse(access.allowedAdmin(post,1,"POST_MODERATE","content:read","post:dispose"));assertTrue(access.allowedAdmin(post,1,"POST_MODERATE","content:read"));publish("POST_MODERATE");
    }
    @Test void unifiedFinalCandidateScopeFiltersBeforeSearchAndPinsMemberCycle() {
        publish("ORG_MEMBER_READ");var foundation=context.getBean(cn.jualn.miniapp.module.person.service.facts.PersonFoundationService.class);
        var change=new cn.jualn.miniapp.module.person.bo.PersonBO.OrganizationChange("Same organization name","",null,"checked","synthetic","PARTY_BRANCH");
        String org=foundation.createOrganization(1,change,key()).value().organizationId(),hidden=foundation.createOrganization(1,change,key()).value().organizationId();
        long delegate=user(),staff=user(),outsider=user();var rootPerson=create("candidate root");var delegatePerson=create("candidate delegate");var staffPerson=create("inside scoped person");var hiddenPerson=create("outside secret person");
        for(var p:List.of(rootPerson,delegatePerson,staffPerson))foundation.changeLocalMembership(1,p.value().personId(),org,new cn.jualn.miniapp.module.person.bo.PersonBO.LocalMemberChange(cn.jualn.miniapp.module.person.bo.PersonBO.LocalMemberKind.JOIN,"checked","synthetic"),key(),foundation.localMembership(1,p.value().personId(),org).etag());
        foundation.changeLocalMembership(1,hiddenPerson.value().personId(),hidden,new cn.jualn.miniapp.module.person.bo.PersonBO.LocalMemberChange(cn.jualn.miniapp.module.person.bo.PersonBO.LocalMemberKind.JOIN,"checked","synthetic"),key(),foundation.localMembership(1,hiddenPerson.value().personId(),hidden).etag());
        // Root membership fixture uses a trusted roster binding; Person/membership/source decisions stay real.
        db.update("INSERT INTO person_account_binding(id,user_id,person_id,status,revision,started_at,verification_method,source_reference,started_by) VALUES(?,1,?,'ACTIVE',1,UTC_TIMESTAMP(6),'ROSTER_EXACT_MATCH','isolated roster',2)",key(),rootPerson.value().personId());
        bindings.createUserPersonBinding(1,bind(delegatePerson,delegate),key());bindings.createUserPersonBinding(1,bind(staffPerson,staff),key());bindings.createUserPersonBinding(1,bind(hiddenPerson,outsider),key());
        var selection=new Selection("ORG_MEMBER_READ",new Target("ORGANIZATION",null,org),expiry(),null);
        var authority=unified().grantAuthority(1,new AuthorityRequest(Long.toString(delegate),List.of(selection),"SAME_ORGANIZATION_MEMBERS",expiry(),"isolated exact source"),key());
        var targets=unified().targetOptions(delegate,authority.authorityId(),"ORG_MEMBER_READ","ORGANIZATION","Same organization name",20,null);
        assertEquals(1,targets.items().size());assertEquals(org,targets.items().get(0).target().organizationId());
        var result=unified().recipientOptions(delegate,authority.authorityId(),"ORG_MEMBER_READ",selection.target(),null,"outside secret",20,null);assertTrue(result.items().isEmpty());
        assertEquals(404,assertThrows(ContractProblemException.class,()->unified().recipientOptions(delegate,authority.authorityId(),"ORG_MEMBER_READ",new Target("ORGANIZATION",null,hidden),null,null,20,null)).getStatus().value());
        assertFalse(reader().read(delegate).allows("organization:read","ORGANIZATION"));
        var dependent=grant(delegate,staff,authority.authorityId(),"ORG_MEMBER_READ",selection.target());
        var first=unified().recipientOptions(delegate,authority.authorityId(),"ORG_MEMBER_READ",selection.target(),null,null,1,null);assertNotNull(first.nextCursor());
        var member=foundation.localMembership(1,staffPerson.value().personId(),org);
        foundation.changeLocalMembership(1,staffPerson.value().personId(),org,new cn.jualn.miniapp.module.person.bo.PersonBO.LocalMemberChange(cn.jualn.miniapp.module.person.bo.PersonBO.LocalMemberKind.REMOVE,"checked","synthetic"),key(),member.etag());
        assertFalse(reader().read(staff).canUseAdmin());
        assertThrows(ContractProblemException.class,()->unified().recipientOptions(delegate,authority.authorityId(),"ORG_MEMBER_READ",selection.target(),null,null,1,first.nextCursor()));
        foundation.changeLocalMembership(1,staffPerson.value().personId(),org,new cn.jualn.miniapp.module.person.bo.PersonBO.LocalMemberChange(cn.jualn.miniapp.module.person.bo.PersonBO.LocalMemberKind.JOIN,"checked","synthetic"),key(),foundation.localMembership(1,staffPerson.value().personId(),org).etag());
        assertFalse(reader().read(staff).canUseAdmin());
    }

    @Test void unifiedFinalOfflineBootstrapAcceptsNewAssignmentAccountsAndPreservesReplay() throws Exception {
        String url=System.getProperty("managed.person.test.jdbcUrl"),bootstrap=url+"_bootstrap",schema=bootstrap.substring(bootstrap.lastIndexOf('/')+1);
        var admin=new org.springframework.jdbc.core.JdbcTemplate(source(url.substring(0,url.lastIndexOf('/'))+"/mysql"));
        assertEquals(0,admin.queryForObject("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",Integer.class,schema));admin.execute("CREATE DATABASE "+schema);
        var ds=source(bootstrap);org.flywaydb.core.Flyway.configure().dataSource(ds).load().migrate();var data=new org.springframework.jdbc.core.JdbcTemplate(ds);
        for(long user:List.of(1L,2L)){
            data.update("INSERT INTO user_profile(id,openid) VALUES(?,?)",user,"bootstrap-user-"+user);
            data.update("INSERT INTO rbac_authorization_authority(user_id,source,version,updated_at) VALUES(?,'ASSIGNMENT',?,UTC_TIMESTAMP(6))",user,key());
        }
        // The standalone executor constructor is deliberately package-private, not an application Bean.
        var constructor=RbacInitialMigration.class.getDeclaredConstructor(String.class,javax.sql.DataSource.class);constructor.setAccessible(true);
        var tool=constructor.newInstance(bootstrap,ds);var plan=tool.inspect("synthetic-final-bootstrap",1,"isolated-operator","explicit isolated approval","a".repeat(64));
        tool.dryRun(plan);assertEquals(0,data.queryForObject("SELECT COUNT(*) FROM rbac_user_role_assignment",Integer.class));
        var result=tool.execute(plan);assertEquals(result,tool.execute(plan));
        assertEquals(1,data.queryForObject("SELECT COUNT(*) FROM rbac_user_role_assignment WHERE role_code='ROOT' AND revoked_at IS NULL",Integer.class));
        assertEquals(0,data.queryForObject("SELECT COUNT(*) FROM rbac_user_role_assignment WHERE user_id=2",Integer.class));
        assertEquals(2,data.queryForObject("SELECT COUNT(*) FROM rbac_authorization_authority WHERE source='ASSIGNMENT'",Integer.class));
        assertThrows(IllegalStateException.class,()->tool.inspect("synthetic-final-bootstrap",2,"isolated-operator","second root denied","b".repeat(64)));
    }

    void sessionActor(long actor){
        var storage=mock(cn.dev33.satoken.context.model.SaStorage.class);Map<String,Object> state=new HashMap<>();
        when(storage.get(anyString())).thenAnswer(i->state.get(i.getArgument(0)));when(storage.set(anyString(),any())).thenAnswer(i->{state.put(i.getArgument(0),i.getArgument(1));return storage;});when(storage.delete(anyString())).thenAnswer(i->{state.remove(i.getArgument(0));return storage;});
        cn.dev33.satoken.SaManager.getSaTokenContext().setContext(mock(cn.dev33.satoken.context.model.SaRequest.class),mock(cn.dev33.satoken.context.model.SaResponse.class),storage);AdminStpUtil.STP_LOGIC.switchTo(actor);
    }
}
