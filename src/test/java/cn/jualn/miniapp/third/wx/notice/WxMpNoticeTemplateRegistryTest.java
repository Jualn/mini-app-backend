package cn.jualn.miniapp.third.wx.notice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.*;

class WxMpNoticeTemplateRegistryTest {

    @Test
    void constructor_rejectsDuplicateBusinessType() {
        WxMpNoticeTemplateProperties properties = new WxMpNoticeTemplateProperties();
        properties.setNoticeTemplates(Map.of(
                "first", template("comment_reply", false),
                "second", template("comment_reply", false)));

        assertThrows(IllegalStateException.class, () -> new WxMpNoticeTemplateRegistry(properties));
    }

    @Test
    void constructor_allowsDisabledIncompleteTemplateButRejectsEnabledOne() {
        WxMpNoticeTemplateProperties properties = new WxMpNoticeTemplateProperties();
        WxMpNoticeTemplateProperties.Template disabled = template("comment_reply", false);
        properties.setNoticeTemplates(Map.of("comment", disabled));
        assertSame(disabled, new WxMpNoticeTemplateRegistry(properties)
                .getRequired(WxMpNoticeType.COMMENT_REPLY));

        properties.setNoticeTemplates(Map.of("comment", template("comment_reply", true)));
        assertThrows(IllegalStateException.class, () -> new WxMpNoticeTemplateRegistry(properties));
    }

    private WxMpNoticeTemplateProperties.Template template(String type, boolean enabled) {
        WxMpNoticeTemplateProperties.Template template = new WxMpNoticeTemplateProperties.Template();
        template.setType(type);
        template.setEnabled(enabled);
        template.setFields(new LinkedHashMap<>());
        return template;
    }

    @Test
    void optionalJumpAndRelativePathAreAllowedButExternalUrlIsNot() {
        var properties = new WxMpNoticeTemplateProperties();
        var template = enabledTemplate("reply");
        properties.setNoticeTemplates(Map.of("reply", template));
        assertTrue(new WxMpNoticeTemplateRegistry(properties).isSendEnabled(WxMpNoticeType.REPLY));
        template.setPagePath("pages/detail?id={targetId}");
        assertDoesNotThrow(() -> new WxMpNoticeTemplateRegistry(properties));
        template.setPagePath("https://untrusted.example/path");
        assertThrows(IllegalStateException.class, () -> new WxMpNoticeTemplateRegistry(properties));
    }

    @Test
    void hiddenTemplateStillSendsAndSharedProviderIdDoesNotCauseDuplicateType() {
        var properties = new WxMpNoticeTemplateProperties();
        var reply = enabledTemplate("reply");
        reply.setSubscribeVisible(false);
        properties.setNoticeTemplates(Map.of("reply", reply, "comment", enabledTemplate("comment_reply")));
        var registry = new WxMpNoticeTemplateRegistry(properties);
        assertTrue(registry.isSendEnabled(WxMpNoticeType.REPLY));
        assertFalse(registry.isSendEnabled(WxMpNoticeType.SYSTEM_NOTICE));
        assertEquals(1, registry.listSubscribeVisibleTemplates().size());
    }

    @Test
    void requiredDefaultsAndTimeTruncationAreRejected() {
        var properties = new WxMpNoticeTemplateProperties();
        var template = enabledTemplate("reply");
        properties.setNoticeTemplates(Map.of("reply", template));
        var field = template.getFields().get("thing1");
        field.setRequired(true);
        field.setDefaultValue("invented");
        assertThrows(IllegalStateException.class, () -> new WxMpNoticeTemplateRegistry(properties));
        field.setDefaultValue(null);
        field.setFormatter("datetime");
        field.setMaxLength(5);
        assertThrows(IllegalStateException.class, () -> new WxMpNoticeTemplateRegistry(properties));
    }

    @Test
    void repositoryYamlBindsAndAllEnabledMappingsRenderFrozenContent() throws Exception {
        MockEnvironment environment = new MockEnvironment();
        new YamlPropertySourceLoader().load("repository", new ClassPathResource("application.yaml"))
                .forEach(source -> environment.getPropertySources().addLast(source));
        var properties = Binder.get(environment).bind("wx.mp", Bindable.of(WxMpNoticeTemplateProperties.class)).get();
        var registry = new WxMpNoticeTemplateRegistry(properties);
        var renderer = new WxMpNoticeFieldRenderer();
        Map<String, Object> frozen = Map.ofEntries(
                Map.entry("auditContent", "内容"), Map.entry("auditResult", "未通过"),
                Map.entry("auditReason", "原因"), Map.entry("auditTime", "2026-09-29T10:30:00"),
                Map.entry("postTitle", "标题"), Map.entry("replyUserName", "同学"),
                Map.entry("replyContent", "回复"), Map.entry("replyTime", "2026-09-29T10:30:00"),
                Map.entry("postId", 1), Map.entry("activityTitle", "活动"),
                Map.entry("activityId", 2), Map.entry("startTime", "2026-09-29T10:30:00"),
                Map.entry("originalContent", "原文"));
        for (var type : new WxMpNoticeType[]{WxMpNoticeType.AUDIT_RESULT, WxMpNoticeType.COMMENT_REPLY,
                WxMpNoticeType.ACTIVITY_START, WxMpNoticeType.REPLY}) {
            assertTrue(registry.isSendEnabled(type));
            var template = registry.getRequired(type);
            assertFalse(renderer.render(template, frozen).isEmpty());
            assertFalse(renderer.resolvePath(template.getPagePath(), frozen).contains("{"));
        }
    }

    private WxMpNoticeTemplateProperties.Template enabledTemplate(String type) {
        var template = template(type, true);
        template.setTemplateId("synthetic-template");
        var field = new WxMpNoticeTemplateProperties.Field();
        field.setSource("title");
        template.setFields(Map.of("thing1", field));
        return template;
    }
}
