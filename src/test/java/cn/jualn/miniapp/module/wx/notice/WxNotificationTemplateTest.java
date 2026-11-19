package cn.jualn.miniapp.module.wx.notice;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** These rules are synthetic fixtures, not inferred WeChat platform limits. */
class WxNotificationTemplateTest {
    @Test void subscriptionEntryUsesEnabledBindingsAndDoesNotExposeMiniTemplates() {
        var p = fixture();
        var r = registry(p);
        assertEquals("fixture-only", r.listSubscribeVisibleTemplates().get(0).getTemplateId());
        p.getBindings().get("start").setEnabled(false);
        assertTrue(registry(p).listSubscribeVisibleTemplates().isEmpty());
        p = fixture();
        p.getDefinitions().get("fixture").setChannel("WECHAT_MINI_PROGRAM");
        p.getDefinitions().get("fixture").setAccountRef("ma-primary");
        p.getDefinitions().get("fixture").setProduct("MINI_PROGRAM_SUBSCRIBE");
        p.getBindings().get("start").setChannel("WECHAT_MINI_PROGRAM");
        p.getBindings().get("start").setAccountRef("ma-primary");
        assertTrue(registry(p).listSubscribeVisibleTemplates().isEmpty());
        var snapshot = new WxNotificationTemplateRenderer().renderBinding(registry(p), "start", frozen());
        assertEquals("formal", snapshot.miniProgramState());
    }

    @Test void genericBindingReadinessDoesNotRequireActivityStartAlias() {
        var p = fixture();
        var binding = p.getBindings().get("start");
        binding.setNotificationType("PUBLIC_EVENT_TIME_CHANGED");
        binding.setJumpTarget("PUBLIC_EVENT_DETAIL");
        binding.getFields().remove("time2");
        p.getDefinitions().get("fixture").getFields().remove("time2");
        var accounts = new cn.jualn.miniapp.third.wx.config.WxProperties(
                new cn.jualn.miniapp.third.wx.config.WxProperties.Mp("fixture-mp", "fixture-secret", null, null, null),
                new cn.jualn.miniapp.third.wx.config.WxProperties.Ma("fixture-ma", "fixture-secret", null, null));
        var service = new cn.jualn.miniapp.module.wx.service.WxMpNoticeSendService(
                org.mockito.Mockito.mock(cn.jualn.miniapp.third.wx.client.WxClient.class), accounts,
                org.mockito.Mockito.mock(cn.jualn.miniapp.module.user.service.UserWechatIdentityService.class), registry(p), new WxNotificationTemplateRenderer());
        assertTrue(service.isDeliveryAvailable(cn.jualn.miniapp.module.notify.enums.NotifyType.PUBLIC_EVENT_TIME_CHANGED));
        assertFalse(service.isDeliveryAvailable(cn.jualn.miniapp.module.notify.enums.NotifyType.PUBLIC_EVENT_CANCELLED));
        var snapshot = service.prepareDelivery("PUBLIC_EVENT_TIME_CHANGED", null, frozen());
        assertTrue(snapshot.pagePath().contains("publicEventId=9"));
    }

    @Test void configuredMiniChannelCannotTreatConfigurationAsPermission() {
        var p = fixture();
        var definition = p.getDefinitions().get("fixture");
        definition.setChannel("WECHAT_MINI_PROGRAM"); definition.setAccountRef("ma-primary");
        definition.setProduct("MINI_PROGRAM_SUBSCRIBE");
        var binding = p.getBindings().get("start");
        binding.setChannel(definition.getChannel()); binding.setAccountRef(definition.getAccountRef());
        var client = org.mockito.Mockito.mock(cn.jualn.miniapp.third.wx.client.WxClient.class);
        var users = org.mockito.Mockito.mock(cn.jualn.miniapp.module.user.service.UserWechatIdentityService.class);
        var service = new cn.jualn.miniapp.module.wx.service.WxMiniNoticeSendService(client,
                new cn.jualn.miniapp.third.wx.config.WxProperties(null,
                    new cn.jualn.miniapp.third.wx.config.WxProperties.Ma("fixture-ma", "fixture-secret", null, null)),
                users, registry(p), new WxNotificationTemplateRenderer(), new WxMiniSubscriptionCapability());
        assertTrue(service.isTemplateConfigured(cn.jualn.miniapp.module.notify.enums.NotifyType.ACTIVITY_START_REMINDER));
        assertFalse(service.isDeliveryAvailable(cn.jualn.miniapp.module.notify.enums.NotifyType.ACTIVITY_START_REMINDER));
        var prepared = service.prepareDelivery(cn.jualn.miniapp.module.notify.enums.NotifyType.ACTIVITY_START_REMINDER, frozen());
        assertThrows(cn.jualn.miniapp.common.exception.BusinessException.class,
                () -> service.sendFrozen(3L, prepared, "7", () -> { }));
        org.mockito.Mockito.verifyNoInteractions(client, users);
    }

    static WxNotificationTemplateProperties fixture() {
        var p = new WxNotificationTemplateProperties(); p.setEnvironment("test");
        var d = new WxNotificationTemplateProperties.Definition();
        d.setChannel("WECHAT_OFFICIAL_ACCOUNT"); d.setAccountRef("mp-primary");
        d.setProduct("OFFICIAL_ACCOUNT_SUBSCRIBE_BIZSEND"); d.setTemplateId("fixture-only");
        d.setEnabled(true); d.setEvidenceRef("test-fixture");
        var title = new WxNotificationTemplateProperties.FieldRule(); title.setRequired(true); title.setMaxLength(3);
        var time = new WxNotificationTemplateProperties.FieldRule(); time.setDataType("DATETIME"); time.setRequired(true);
        d.getFields().put("thing1",title); d.getFields().put("time2",time); p.getDefinitions().put("fixture",d);
        var b = new WxNotificationTemplateProperties.Binding(); b.setNotificationType("ACTIVITY_START_REMINDER");
        b.setChannel(d.getChannel()); b.setAccountRef(d.getAccountRef()); b.setDefinitionRef("fixture");
        b.setEnabled(true); b.setJumpTarget("ACTIVITY_DETAIL");
        var tm = new WxNotificationTemplateProperties.Mapping(); tm.setSource("subjectTitle"); tm.setOverflow("truncate");
        var dt = new WxNotificationTemplateProperties.Mapping(); dt.setSource("businessTime"); dt.setFormatter("datetime");
        b.getFields().put("thing1",tm); b.getFields().put("time2",dt); p.getBindings().put("start",b);
        return p;
    }
    static WxNotificationTemplateRegistry registry(WxNotificationTemplateProperties p) {
        return new WxNotificationTemplateRegistry(new WxMpNoticeTemplateProperties(),p);
    }
    static Map<String,Object> frozen() {
        return Map.of("notificationId","7","targetId","9","businessTime","2027-03-01T00:30:00Z",
                "presentation",Map.of("subjectTitle","A😀中尾"));
    }
    @Test void offlinePreviewUsesUnicodeAndExplicitTimezoneAndOriginalNotification() {
        var result=new WxNotificationTemplateRenderer().preview(registry(fixture()),"start",frozen());
        assertTrue(result.valid()); assertEquals("A😀中",result.snapshot().data().get("thing1"));
        assertEquals("2027-03-01 08:30",result.snapshot().data().get("time2"));
        assertEquals("/subpkg_activity/pages/detail/detail?activityId=9&notificationId=7",result.snapshot().pagePath());
        assertNull(result.snapshot().accountAppId()); assertNull(result.snapshot().jumpAppId());
    }
    @Test void missingTimeAndInvalidDateAndTargetAreRejectedWithoutPayloadEcho() {
        for(String value:new String[]{"impossible","2027-02-30T10:30:00",""}) {
            var f=new java.util.HashMap<>(frozen()); f.put("businessTime",value);
            assertFalse(new WxNotificationTemplateRenderer().preview(registry(fixture()),"start",f).valid());
        }
        var f=new java.util.HashMap<>(frozen()); f.remove("businessTime");
        assertFalse(new WxNotificationTemplateRenderer().preview(registry(fixture()),"start",f).valid());
        f.put("businessTime","2027-03-01T10:30:00"); f.put("targetId","../other");
        assertEquals(java.util.List.of("TEMPLATE_INPUT_INVALID"),new WxNotificationTemplateRenderer().preview(registry(fixture()),"start",f).errors());
    }
    @Test void missingDefinitionDuplicateBindingAndUncontrolledSourceFailStartup() {
        var missing=fixture(); missing.getDefinitions().clear(); assertThrows(IllegalStateException.class,()->registry(missing));
        var duplicate=fixture(); duplicate.getBindings().put("another",duplicate.getBindings().get("start"));
        assertThrows(IllegalStateException.class,()->registry(duplicate));
        var expression=fixture(); expression.getBindings().get("start").getFields().get("thing1").setSource("user.password");
        assertThrows(IllegalStateException.class,()->registry(expression));
        var mismatch=fixture(); mismatch.getBindings().get("start").setChannel("WECHAT_MINI_PROGRAM");
        assertThrows(IllegalStateException.class,()->registry(mismatch));
    }
    @Test void legalSharingAndChannelIsolationAreSupportedWithoutEnablingAdapter() {
        var p=fixture(); var json=new com.fasterxml.jackson.databind.ObjectMapper();
        var shared=json.convertValue(p.getBindings().get("start"),WxNotificationTemplateProperties.Binding.class);
        shared.setNotificationType("ACTIVITY_REGISTRATION_START_REMINDER");p.getBindings().put("registration",shared);
        var mini=json.convertValue(p.getDefinitions().get("fixture"),WxNotificationTemplateProperties.Definition.class);
        mini.setChannel("WECHAT_MINI_PROGRAM");mini.setAccountRef("ma-primary");mini.setProduct("MINI_PROGRAM_SUBSCRIBE");
        p.getDefinitions().put("mini",mini);
        var binding=json.convertValue(p.getBindings().get("start"),WxNotificationTemplateProperties.Binding.class);
        binding.setChannel(mini.getChannel());binding.setAccountRef(mini.getAccountRef());binding.setDefinitionRef("mini");p.getBindings().put("mini-start",binding);
        var r=registry(p);assertEquals("start",r.bindingRef("ACTIVITY_START_REMINDER","WECHAT_OFFICIAL_ACCOUNT"));
        assertEquals("mini-start",r.bindingRef("ACTIVITY_START_REMINDER","WECHAT_MINI_PROGRAM"));
        assertTrue(new WxNotificationTemplateRenderer().preview(r,"registration",frozen()).valid());
    }
    @Test void runningRegistryIsDetachedAndRenderingStableForSameVersion() {
        var p=fixture();var r=registry(p);var renderer=new WxNotificationTemplateRenderer();
        var old=renderer.renderBinding(r,"start",frozen());p.getDefinitions().get("fixture").setTemplateId("changed");
        p.getBindings().get("start").setEnabled(false);
        assertEquals(old,renderer.renderBinding(r,"start",frozen()));assertTrue(r.isSnapshotEnabled(old));
        assertFalse(registry(p).isSnapshotEnabled(old));
    }
    @Test void textOverflowEnumsNumbersAndFormatsUseDefinitionNotFieldName() {
        var p=fixture();p.getBindings().get("start").getFields().get("thing1").setOverflow("reject");
        assertFalse(new WxNotificationTemplateRenderer().preview(registry(p),"start",frozen()).valid());
        p=fixture();p.getDefinitions().get("fixture").getFields().get("thing1").setAllowedValues(java.util.List.of("other"));
        assertFalse(new WxNotificationTemplateRenderer().preview(registry(p),"start",frozen()).valid());
        p=fixture();p.getDefinitions().get("fixture").getFields().get("time2").setDataType("NUMBER");
        p.getBindings().get("start").getFields().get("time2").setFormatter("number");
        var f=new java.util.HashMap<>(frozen());f.put("businessTime","12.50");
        assertEquals("12.50",new WxNotificationTemplateRenderer().preview(registry(p),"start",f).snapshot().data().get("time2"));
    }
    @Test void localBusinessTimeHasExplicitSourceZoneAndDigestIgnoresMapInsertionOrder() {
        var p=fixture();p.getBindings().get("start").getFields().get("time2").setZone("UTC");
        var f=new java.util.HashMap<>(frozen());f.put("businessTime","2027-03-01T10:30:00");
        assertEquals("2027-03-01 02:30",new WxNotificationTemplateRenderer().preview(registry(p),"start",f).snapshot().data().get("time2"));
        var first=new java.util.LinkedHashMap<String,Object>();first.put("a",1);first.put("b",2);
        var second=new java.util.LinkedHashMap<String,Object>();second.put("b",2);second.put("a",1);
        assertEquals(WxNotificationTemplateRenderer.configurationDigest(first),WxNotificationTemplateRenderer.configurationDigest(second));
    }
    @Test void offlineCliUsesFixtureConfigurationWithoutAccountOrIdentity() throws Exception {
        var original=System.out;var output=new java.io.ByteArrayOutputStream();
        try {System.setOut(new java.io.PrintStream(output,true,java.nio.charset.StandardCharsets.UTF_8));
            WxTemplatePreview.main(new String[]{"src/test/resources/wechat-template-preview.yaml","src/test/resources/wechat-template-preview.json","fixture-activity-start"});
        } finally {System.setOut(original);}
        var result=new com.fasterxml.jackson.databind.ObjectMapper().readTree(output.toString(java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(result.get("valid").asBoolean());assertTrue(result.get("snapshot").get("accountAppId").isNull());
        assertEquals("A😀中",result.get("snapshot").get("data").get("thing1").asText());
    }

    @Test void explicitBindingSnapshotIsNotRerenderedAfterVersionAndTemplateChange() {
        var client=org.mockito.Mockito.mock(cn.jualn.miniapp.third.wx.client.WxClient.class);
        var users=org.mockito.Mockito.mock(cn.jualn.miniapp.module.user.service.UserWechatIdentityService.class);
        var accounts=new cn.jualn.miniapp.third.wx.config.WxProperties(
                new cn.jualn.miniapp.third.wx.config.WxProperties.Mp("fixture-mp","fixture-secret",null,null,null),
                new cn.jualn.miniapp.third.wx.config.WxProperties.Ma("fixture-ma","fixture-secret",null,null));
        var p=fixture();var renderer=new WxNotificationTemplateRenderer();
        var old=new cn.jualn.miniapp.module.wx.service.WxMpNoticeSendService(client,accounts,users,registry(p),renderer);
        assertTrue(old.isDeliveryAvailable(cn.jualn.miniapp.module.notify.enums.NotifyType.ACTIVITY_START_REMINDER));
        var snapshot=old.prepareDelivery("ACTIVITY_START_REMINDER",WxMpNoticeType.ACTIVITY_START,frozen());
        org.mockito.Mockito.verifyNoInteractions(client,users);
        p.getDefinitions().get("fixture").setVersion(2);p.getDefinitions().get("fixture").setTemplateId("new-fixture-id");
        p.getBindings().get("start").setVersion(2);p.getBindings().get("start").getFields().get("thing1").setSource("title");
        var current=new cn.jualn.miniapp.module.wx.service.WxMpNoticeSendService(client,accounts,users,registry(p),renderer);
        org.mockito.Mockito.when(users.getOfficialAccountOpenid(3L)).thenReturn("fixture-user");
        var response=new cn.jualn.miniapp.third.wx.dto.MpSubscribeMessageResponse();response.setErrcode(0);
        org.mockito.Mockito.when(client.sendMpSubscribeMessage(org.mockito.ArgumentMatchers.any())).thenReturn(response);
        current.sendFrozen(3L,snapshot,"7",()->{});
        var capture=org.mockito.ArgumentCaptor.forClass(cn.jualn.miniapp.third.wx.dto.MpSubscribeMessageRequest.class);
        org.mockito.Mockito.verify(client).sendMpSubscribeMessage(capture.capture());
        assertEquals("fixture-only",capture.getValue().getTemplateId());assertEquals("A😀中",capture.getValue().getData().get("thing1").getValue());
        assertEquals(1,snapshot.definitionVersion());assertEquals(1,snapshot.bindingVersion());
    }

    @Test void profileEnvironmentIsExplicitAndNewDefinitionsStayEmptyByDefault() throws Exception {
        for(String profile:java.util.List.of("dev","prod")) {
            var env=new org.springframework.mock.env.MockEnvironment();
            new org.springframework.boot.env.YamlPropertySourceLoader().load("profile",
                    new org.springframework.core.io.ClassPathResource("application-"+profile+".yaml"))
                    .forEach(source->env.getPropertySources().addLast(source));
            var properties=org.springframework.boot.context.properties.bind.Binder.get(env).bind("wx.notification",
                    org.springframework.boot.context.properties.bind.Bindable.of(WxNotificationTemplateProperties.class)).get();
            assertEquals(profile,properties.getEnvironment());assertTrue(properties.getDefinitions().isEmpty());
        }
    }

    @Test void missingSubjectTitleCannotBeGuessedFromNotificationHeadline() {
        var f=new java.util.HashMap<>(frozen());f.remove("presentation");f.put("title","notification headline");
        var preview=new WxNotificationTemplateRenderer().preview(registry(fixture()),"start",f);
        assertFalse(preview.valid());assertEquals(java.util.List.of("FIELD:thing1:REQUIRED"),preview.errors());
    }

}
