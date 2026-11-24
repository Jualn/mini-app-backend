package cn.jualn.miniapp.module.wx.notice;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
class WxNotificationTemplateTest {
    static WxNotificationTemplateProperties fixture(){
        var p=new WxNotificationTemplateProperties();p.setEnvironment("test");
        var d=new WxNotificationTemplateProperties.Definition();d.setChannel("WECHAT_MINI_PROGRAM");d.setAccountRef("ma-primary");d.setProduct("MINI_PROGRAM_SUBSCRIBE");d.setTemplateId("fixture-only");d.setTitle("合成活动提醒");
        var text=new WxNotificationTemplateProperties.FieldRule();text.setRequired(true);text.setMaxLength(3);
        var time=new WxNotificationTemplateProperties.FieldRule();time.setDataType("DATETIME");time.setRequired(true);
        d.getFields().put("thing1",text);d.getFields().put("time2",time);p.getDefinitions().put("fixture",d);
        var b=new WxNotificationTemplateProperties.Binding();b.setNotificationType("ACTIVITY_START_REMINDER");b.setChannel(d.getChannel());b.setAccountRef(d.getAccountRef());b.setDefinitionRef("fixture");b.setJumpTarget("ACTIVITY_DETAIL");
        var name=new WxNotificationTemplateProperties.Mapping();name.setSource("subjectTitle");name.setOverflow("truncate");
        var when=new WxNotificationTemplateProperties.Mapping();when.setSource("businessTime");when.setFormatter("datetime");
        b.getFields().put("thing1",name);b.getFields().put("time2",when);p.getBindings().put("start",b);return p;
    }
    static WxNotificationTemplateRegistry registry(WxNotificationTemplateProperties p){return new WxNotificationTemplateRegistry(p,null);}
    static Map<String,Object> frozen(){return Map.of("notificationId","7","targetId","9","businessTime","2027-03-01T00:30:00Z","presentation",Map.of("subjectTitle","A😀中尾"));}
    @Test void unicodeTimeAndOriginIdentityAreFrozen(){
        var r=registry(fixture());var snapshot=new WxNotificationTemplateRenderer().renderBinding(r,"start",frozen());
        assertEquals("A😀中",snapshot.data().get("thing1"));assertEquals("2027-03-01 08:30",snapshot.data().get("time2"));assertEquals("/subpkg_activity/pages/detail/detail?activityId=9&notificationId=7",snapshot.pagePath());
    }
    @Test void invalidOrMissingFrozenFactsCannotBeInvented(){
        var renderer=new WxNotificationTemplateRenderer();var r=registry(fixture());
        for(String time:java.util.List.of("impossible","2027-02-30T10:30:00","")){
            var input=new java.util.HashMap<>(frozen());input.put("businessTime",time);assertFalse(renderer.preview(r,"start",input).valid());
        }
        var input=new java.util.HashMap<>(frozen());input.remove("presentation");input.put("title","not the subject title");
        assertFalse(renderer.preview(r,"start",input).valid());
    }
    @Test void offlineFixturePreviewsWithoutAnAccountOrPermission()throws Exception{
        var env=new org.springframework.mock.env.MockEnvironment();
        new org.springframework.boot.env.YamlPropertySourceLoader().load("fixture",new org.springframework.core.io.ClassPathResource("wechat-template-preview.yaml")).forEach(v->env.getPropertySources().addLast(v));
        var configuration=org.springframework.boot.context.properties.bind.Binder.get(env).bind("wx.notification",org.springframework.boot.context.properties.bind.Bindable.of(WxNotificationTemplateProperties.class)).get();
        Map<String,Object> input=new com.fasterxml.jackson.databind.ObjectMapper().readValue(java.nio.file.Path.of("src/test/resources/wechat-template-preview.json").toFile(),new com.fasterxml.jackson.core.type.TypeReference<>(){});
        var result=new WxNotificationTemplateRenderer().preview(registry(configuration),"fixture-activity-start",input);
        assertTrue(result.valid(),result.errors().toString());assertNull(result.snapshot().accountAppId());
    }
}
