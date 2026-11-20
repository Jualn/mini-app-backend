package cn.jualn.miniapp.module.notify;

import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.module.notify.bo.NotificationDeliveryBO;
import cn.jualn.miniapp.module.notify.channel.NotificationChannels;
import cn.jualn.miniapp.module.notify.enums.NotifyType;
import cn.jualn.miniapp.module.notify.mapper.NotificationMonitoringMapper;
import cn.jualn.miniapp.module.notify.model.NotificationCategory;
import cn.jualn.miniapp.module.notify.service.NotificationMonitoringService;
import cn.jualn.miniapp.module.notify.service.capability.NotificationCapabilityQueries;
import cn.jualn.miniapp.module.notify.service.capability.NotificationCatalogQueries;
import cn.jualn.miniapp.module.wx.notice.WxNoticeFieldCatalog;
import cn.jualn.miniapp.module.wx.notice.WxNotificationChannelAdapter;
import cn.jualn.miniapp.module.wx.notice.WxNotificationTemplateProperties;
import cn.jualn.miniapp.module.wx.notice.WxNotificationTemplateRegistry;
import cn.jualn.miniapp.module.wx.notice.WxNotificationTemplateRenderer;
import cn.jualn.miniapp.module.wx.service.WxMiniNoticeSendService;
import cn.jualn.miniapp.module.wx.service.WxMpNoticeSendService;
import cn.jualn.miniapp.third.wx.client.WxClient;
import cn.jualn.miniapp.third.wx.config.WxProperties;
import cn.jualn.miniapp.module.user.service.UserCurrentLoginAccess;
import cn.jualn.miniapp.module.user.service.UserNotificationIdentityService;
import cn.jualn.miniapp.module.user.service.UserWechatIdentityService;
import cn.jualn.miniapp.module.authorization.service.CurrentAuthorization;
import cn.jualn.miniapp.module.authorization.service.RbacAuthorizationReader;
import cn.jualn.miniapp.module.authorization.model.RbacModels.Decision;
import cn.jualn.miniapp.module.authorization.model.RbacModels.Grant;
import cn.jualn.miniapp.module.authorization.model.RbacModels.OwnerCapability;
import cn.jualn.miniapp.module.authorization.model.RbacModels.Source;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class NotificationFirstReleaseTest {
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    final WxProperties accounts=new WxProperties(new WxProperties.Mp("fixture-mp","synthetic-secret",null,null,null),new WxProperties.Ma("fixture-ma","synthetic-secret",null,null));
    final WxClient provider=mock(WxClient.class);
    @BeforeEach void login(){UserContext.setUserId(2L);}
    @AfterEach void logout(){UserContext.clear();}
    WxNotificationTemplateProperties configuration()throws Exception{
        var env=new org.springframework.mock.env.MockEnvironment();
        // Retired deployment flags do not disable a valid template.
        env.setProperty("WX_NOTIFICATION_MINI_PROGRAM_ENABLED","false");env.setProperty("WX_NOTIFICATION_OFFICIAL_ACCOUNT_ENABLED","true");
        new org.springframework.boot.env.YamlPropertySourceLoader().load("base",new org.springframework.core.io.ClassPathResource("application.yaml")).forEach(s->env.getPropertySources().addLast(s));
        var properties=org.springframework.boot.context.properties.bind.Binder.get(env).bind("wx.notification",org.springframework.boot.context.properties.bind.Bindable.of(WxNotificationTemplateProperties.class)).get();
        // Keep the foundation fault-injection fixture isolated; deployment templates have their own suite.
        properties.getBindings().entrySet().removeIf(e->!e.getKey().equals("mini-post-comment"));
        properties.getDefinitions().entrySet().removeIf(e->!e.getKey().equals("mini-post-comment"));
        return properties;
    }
    WxNotificationTemplateRegistry registry(WxNotificationTemplateProperties p){return new WxNotificationTemplateRegistry(p,accounts);}
    NotificationChannels channels(WxNotificationTemplateProperties p){
        var r=registry(p);var users=mock(UserWechatIdentityService.class);
        var mini=new WxMiniNoticeSendService(provider,accounts,users,r,new WxNotificationTemplateRenderer());
        var official=new WxMpNoticeSendService(provider,accounts,users,r,new WxNotificationTemplateRenderer());
        return new NotificationChannels(List.of(new WxNotificationChannelAdapter(mini.channel(),mini,mini::isTemplateConfigured,json,r),new WxNotificationChannelAdapter(official.channel(),official,official::isDeliveryAvailable,json,r)));
    }
    void add(WxNotificationTemplateProperties p,NotifyType type,String id,String title){
        var d=new WxNotificationTemplateProperties.Definition();d.setChannel("WECHAT_MINI_PROGRAM");d.setAccountRef("ma-primary");d.setProduct("MINI_PROGRAM_SUBSCRIBE");d.setTemplateId(id);d.setTitle(title);
        var field=new WxNotificationTemplateProperties.FieldRule();field.setRequired(true);d.getFields().put("thing1",field);
        var m=new WxNotificationTemplateProperties.Mapping();m.setSource("noticeSummary");
        var b=new WxNotificationTemplateProperties.Binding();b.setNotificationType(type.name());b.setChannel(d.getChannel());b.setAccountRef(d.getAccountRef());b.setDefinitionRef(type.name());b.setJumpTarget("INBOX");b.getFields().put("thing1",m);
        p.getDefinitions().put(type.name(),d);p.getBindings().put(type.name(),b);
    }
    @Test void fullNinetyNineCombinationsKeepOfficialDisabledAndUnconfiguredTypesHonest()throws Exception{
        assertEquals(33,NotificationCategory.types().size());
        var identities=mock(UserNotificationIdentityService.class);when(identities.identity(2L)).thenReturn(new UserNotificationIdentityService.Identity(true,true));
        var values=new NotificationCapabilityQueries(identities,channels(configuration())).getCapabilities().items();
        assertEquals(99,values.size());assertEquals(99,values.stream().map(i->i.notificationType()+":"+i.channel()).distinct().count());
        assertEquals(33,values.stream().filter(i->i.channel().equals("IN_APP")&&i.available()).count());
        assertTrue(values.stream().filter(i->i.channel().equals("WECHAT_OFFICIAL_ACCOUNT")).allMatch(i->!i.available()&&i.permission().equals("UNKNOWN")&&i.unavailableReasons().contains("CHANNEL_DISABLED")));
        var comment=values.stream().filter(i->i.notificationType().equals("POST_COMMENTED")&&i.channel().equals("WECHAT_MINI_PROGRAM")).findFirst().orElseThrow();
        assertTrue(comment.available());assertEquals("PROVIDER_VERIFIED_AT_SEND",comment.permission());
        assertTrue(values.stream().filter(i->i.notificationType().equals("POINT_APPLICATION_APPROVED")&&i.channel().equals("WECHAT_MINI_PROGRAM")).allMatch(i->!i.available()&&i.unavailableReasons().contains("NOT_CONFIGURED")));
        verifyNoInteractions(provider);
    }
    @Test void addingEveryLegalTypeNeedsOnlyTemplateMappingAndSafeRecovery()throws Exception{
        var p=new WxNotificationTemplateProperties();
        for(var type:NotificationCategory.types())add(p,type,"synthetic-"+type,"提醒 "+type.getDesc());
        var r=registry(p);
        for(var type:NotificationCategory.types()){
            var template=r.resolve(type.name(),"WECHAT_MINI_PROGRAM");assertNotNull(template,type.name());
            var snapshot=new WxNotificationTemplateRenderer().renderTemplate(r.environment(),template,Map.of("notificationId","71","noticeSummary","安全摘要"));
            assertEquals("安全摘要",snapshot.data().get("thing1"));assertEquals("/pages/message/message?notificationId=71",snapshot.pagePath());
        }
        assertEquals(33,new NotificationCatalogQueries(channels(p)).options(null).items().size());
    }
    @Test void directoryUsesRealNativeTitleAndDeduplicatesWithinCategoryFilter()throws Exception{
        var p=configuration();add(p,NotifyType.SYSTEM,"synthetic-shared","状态更新");add(p,NotifyType.AUDIT_RESULT,"synthetic-shared","状态更新");
        var queries=new NotificationCatalogQueries(channels(p));var options=queries.options(null);
        assertEquals(2,options.items().size());assertEquals(1,options.maxTemplatesPerRequest());
        var real=options.items().stream().filter(i->i.templateId().startsWith("BG9O")).findFirst().orElseThrow();
        assertEquals("新的评论提醒",real.title());assertEquals("ONE_TIME",real.subscriptionKind());assertEquals(List.of("COMMENT"),real.categories());
        var shared=options.items().stream().filter(i->i.templateId().equals("synthetic-shared")).findFirst().orElseThrow();assertEquals(2,shared.notificationTypes().size());
        assertEquals(List.of("SYSTEM"),queries.options(NotificationCategory.SYSTEM).items().get(0).notificationTypes());
        assertEquals(0,queries.options(NotificationCategory.POINTS).items().size());verifyNoInteractions(provider);
    }
    @Test void badMappingOrUnknownKindIsLocalAndVisibleToMonitoring()throws Exception{
        var p=configuration();add(p,NotifyType.SYSTEM,"synthetic-system","系统消息");p.getBindings().get("SYSTEM").getFields().get("thing1").setSource("arbitrary.secret.path");
        var c=channels(p);assertTrue(c.available("WECHAT_MINI_PROGRAM",NotifyType.POST_COMMENTED));assertFalse(c.available("WECHAT_MINI_PROGRAM",NotifyType.SYSTEM));
        assertEquals(List.of("MAPPING_INVALID"),c.status("WECHAT_MINI_PROGRAM",NotifyType.SYSTEM).issues());
        assertEquals(1,new NotificationCatalogQueries(c).options(null).items().size());
        p.getDefinitions().get("mini-post-comment").setSubscriptionKind("UNVERIFIED");
        assertEquals(List.of("SUBSCRIPTION_KIND_UNKNOWN"),channels(p).status("WECHAT_MINI_PROGRAM",NotifyType.POST_COMMENTED).issues());
    }
    @Test void conflictingNativeMetadataForOneTemplateDoesNotInventGrouping()throws Exception{
        var p=configuration();add(p,NotifyType.SYSTEM,"same-native-template","标题一");add(p,NotifyType.AUDIT_RESULT,"same-native-template","标题二");
        var c=channels(p);assertEquals("INVALID",c.status("WECHAT_MINI_PROGRAM",NotifyType.SYSTEM).status());assertEquals("INVALID",c.status("WECHAT_MINI_PROGRAM",NotifyType.AUDIT_RESULT).status());
        assertEquals(1,new NotificationCatalogQueries(c).options(null).items().size());
    }
    @Test void revisionFollowsAccountAndMappingWithoutExposingCredentials()throws Exception{
        var p=configuration();var first=registry(p).revision();
        var other=new WxProperties(null,new WxProperties.Ma("different-app","other-secret",null,null));
        assertNotEquals(first,new WxNotificationTemplateRegistry(p,other).revision());
        var status=new NotificationCatalogQueries(channels(p)).status();assertEquals(99,status.items().size());
        var text=json.writeValueAsString(status);assertFalse(text.contains("synthetic-secret"));assertFalse(text.contains("fixture-ma"));assertFalse(text.contains("fields"));
    }
    @Test void pointsAndAuditCannotMapPrivateNotesOrMaterial(){
        for(var type:List.of("POINT_APPLICATION_APPROVED","POINT_APPLICATION_REJECTED","POINT_GRANT_REVERSED","POINT_GRANT_CORRECTED","AUDIT_RESULT","POST_REPORT_RESOLVED")){
            for(var field:List.of("content","auditReason","originalContent","signatureUrl","reviewReason","recipientBindingId"))assertFalse(WxNoticeFieldCatalog.allows(type,field),type+":"+field);
            assertTrue(WxNoticeFieldCatalog.allows(type,"noticeSummary"));
        }
    }
    @Test void monitorRequiresOnePlatformNoticeWitnessOrReadyRootOwner(){
        var reader=mock(RbacAuthorizationReader.class);var auth=new CurrentAuthorization(reader);
        var platform=new cn.jualn.miniapp.module.authorization.model.UnifiedModels.Target("PLATFORM",null,null);
        var wrong=new cn.jualn.miniapp.module.authorization.model.UnifiedModels.Target("ORGANIZATION",null,"1");
        for(var grant:List.of(new Grant("g","NOTICE_MONITOR","NOTIFICATION","GLOBAL",List.of("notice:read"),null),new Grant("g","NOTICE_MONITOR","NOTIFICATION","GLOBAL",List.of("notice:read"),null,"NOTICE_MONITOR",wrong))){
            when(reader.read(9)).thenReturn(new Decision(9,Source.ASSIGNMENT,"v","c",LocalDateTime.now(),true,List.of(),List.of(grant),List.of()));
            assertThrows(BusinessException.class,()->auth.requirePlatform(9,"notice:read","NOTIFICATION","NOTICE_MONITOR","notice:read"));
        }
        var valid=new Grant("g","NOTICE_MONITOR","NOTIFICATION","GLOBAL",List.of("notice:read"),null,"NOTICE_MONITOR",platform);
        when(reader.read(9)).thenReturn(new Decision(9,Source.ASSIGNMENT,"v","c",LocalDateTime.now(),true,List.of(),List.of(valid),List.of()));
        assertDoesNotThrow(()->auth.requirePlatform(9,"notice:read","NOTIFICATION","NOTICE_MONITOR","notice:read"));
        when(reader.read(9)).thenReturn(new Decision(9,Source.LEGACY,"v","c",LocalDateTime.now(),true,List.of("*"),List.of(),List.of()));
        assertThrows(BusinessException.class,()->auth.requirePlatform(9,"notice:read","NOTIFICATION","NOTICE_MONITOR","notice:read"));
    }
    @Test void personBindingMustStillMatchBeforePointsOrLearningDelivery(){
        var persons=mock(cn.jualn.miniapp.module.person.service.access.PersonBusinessScopes.class);
        var login=mock(UserCurrentLoginAccess.class);
        var points=new cn.jualn.miniapp.module.points.service.PointsNotificationEligibility(persons,login);
        var learning=new cn.jualn.miniapp.module.learningtask.service.LearningTaskNotificationEligibility(persons,login);
        var evidence=Map.<String,Object>of("recipientPersonId","person-1","recipientBindingId","binding-original");
        var point=new NotificationDeliveryBO(NotifyType.POINT_APPLICATION_APPROVED.getCode(),null,null,2L,false,null);
        var task=new NotificationDeliveryBO(NotifyType.LEARNING_TASK_ASSIGNED.getCode(),null,null,2L,false,null);
        when(persons.deliveryBinding("person-1")).thenReturn(new cn.jualn.miniapp.module.person.service.access.PersonBusinessScopes.DeliveryBinding("ACTIVE",2L,"binding-original"));
        assertTrue(points.isEligible(point,evidence));assertTrue(learning.isEligible(task,evidence));
        when(persons.deliveryBinding("person-1")).thenReturn(new cn.jualn.miniapp.module.person.service.access.PersonBusinessScopes.DeliveryBinding("ACTIVE",2L,"new-binding"));
        assertFalse(points.isEligible(point,evidence));assertFalse(learning.isEligible(task,evidence));
        when(persons.deliveryBinding("person-1")).thenReturn(new cn.jualn.miniapp.module.person.service.access.PersonBusinessScopes.DeliveryBinding("VERIFICATION_REQUIRED",null,null));
        assertThrows(IllegalStateException.class,()->points.isEligible(point,evidence));
        assertFalse(points.isEligible(point,Map.of()));
        assertTrue(learning.isEligible(task,Map.of())); // ACCOUNT responsibility retains its own account check.
    }
    @Test void onlyTheCurrentRootOwnerCapabilityAuthorizesMonitoring(){
        var reader=mock(RbacAuthorizationReader.class);var auth=new CurrentAuthorization(reader);
        var capability=new OwnerCapability("root","NOTIFICATION","GLOBAL",List.of("notice:read"));
        when(reader.read(9)).thenReturn(new Decision(9,Source.ASSIGNMENT,"v","c",LocalDateTime.now(),true,List.of(),List.of(),List.of(capability)));
        assertThrows(BusinessException.class,()->auth.requirePlatform(9,"notice:read","NOTIFICATION","NOTICE_MONITOR","notice:read"));
        var root=new Grant("root","ROOT","AUTHORIZATION","GLOBAL",List.of("authorization:root-manage"),null);
        when(reader.read(9)).thenReturn(new Decision(9,Source.ASSIGNMENT,"v","c",LocalDateTime.now(),true,List.of(),List.of(root),List.of(capability)));
        assertDoesNotThrow(()->auth.requirePlatform(9,"notice:read","NOTIFICATION","NOTICE_MONITOR","notice:read"));
    }
    @Test void monitoringKeepsUnknownSeparateAndNeverReturnsFalseZeroOnDependencyFailure()throws Exception{
        var auth=mock(CurrentAuthorization.class);var mapper=mock(NotificationMonitoringMapper.class);var service=new NotificationMonitoringService(auth,new NotificationCatalogQueries(channels(configuration())),mapper);
        var from=OffsetDateTime.now().minusHours(1);var until=from.plusHours(1);
        when(mapper.summary(any(),any(),any(),any())).thenReturn(List.of(Map.of("notificationType",19,"channel","WECHAT_MINI_PROGRAM","status","UNKNOWN","total",1L)));
        var result=service.summary(9L,from,until,null,null);assertEquals("UNKNOWN",result.items().get(0).outcome());assertEquals("PROVIDER_OUTCOME_UNKNOWN",result.items().get(0).reason());
        verify(auth).requirePlatform(9L,"notice:read","NOTIFICATION","NOTICE_MONITOR","notice:read");
        when(mapper.unknown(any(),any(),any())).thenReturn(1L);assertThrows(ContractProblemException.class,()->service.summary(9L,from,until,null,null));
        when(mapper.unknown(any(),any(),any())).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("synthetic"));assertThrows(ContractProblemException.class,()->service.summary(9L,from,until,null,null));
        assertThrows(ContractProblemException.class,()->service.summary(9L,until,from,null,null));
        assertThrows(ContractProblemException.class,()->service.summary(9L,from,from.plusDays(32),null,null));
        verifyNoInteractions(provider);
    }
}
