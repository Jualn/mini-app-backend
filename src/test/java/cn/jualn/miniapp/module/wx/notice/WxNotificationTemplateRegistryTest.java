package cn.jualn.miniapp.module.wx.notice;

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

class WxNotificationTemplateRegistryTest {
    @Test void effectiveCatalogIsImmutableAndAliasesUseTheSameValidatedEntry() {
        var source = new WxMpNoticeTemplateProperties();
        var configured = enabledTemplate("reply");
        source.setNoticeTemplates(Map.of("reply", configured));
        var registry = new WxNotificationTemplateRegistry(source);
        var effective = registry.resolve("reply", "WECHAT_OFFICIAL_ACCOUNT");
        assertSame(effective, registry.resolve("REPLIED_ME", "WECHAT_OFFICIAL_ACCOUNT"));
        configured.setEnabled(false);
        configured.getFields().clear();
        assertTrue(effective.enabled());
        assertFalse(effective.fields().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> effective.fields().clear());
        assertNull(registry.resolve("reply", "WECHAT_MINI_PROGRAM"));
    }

    @Test void explicitlyDisabledBindingCannotFallBackToEnabledLegacyTemplate() {
        var source = new WxMpNoticeTemplateProperties();
        source.setNoticeTemplates(Map.of("activity", enabledTemplate("activity_start")));
        var explicit = WxNotificationTemplateTest.fixture();
        explicit.getBindings().get("start").setEnabled(false);
        var registry = new WxNotificationTemplateRegistry(source, explicit);
        assertFalse(registry.resolve("ACTIVITY_START_REMINDER", "WECHAT_OFFICIAL_ACCOUNT").enabled());
        assertTrue(registry.resolve("ACTIVITY_REMIND", "WECHAT_OFFICIAL_ACCOUNT").enabled());
    }

    @Test
    void constructor_rejectsDuplicateBusinessType() {
        WxMpNoticeTemplateProperties properties = new WxMpNoticeTemplateProperties();
        properties.setNoticeTemplates(Map.of(
                "first", template("comment_reply", false),
                "second", template("comment_reply", false)));

        assertThrows(IllegalStateException.class, () -> new WxNotificationTemplateRegistry(properties));
    }

    @Test
    void constructor_allowsDisabledIncompleteTemplateButRejectsEnabledOne() {
        WxMpNoticeTemplateProperties properties = new WxMpNoticeTemplateProperties();
        WxMpNoticeTemplateProperties.Template disabled = template("comment_reply", false);
        properties.setNoticeTemplates(Map.of("comment", disabled));
        assertFalse(new WxNotificationTemplateRegistry(properties)
                .resolve("comment_reply", "WECHAT_OFFICIAL_ACCOUNT").enabled());

        properties.setNoticeTemplates(Map.of("comment", template("comment_reply", true)));
        assertThrows(IllegalStateException.class, () -> new WxNotificationTemplateRegistry(properties));
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
        assertTrue(new WxNotificationTemplateRegistry(properties).resolve("reply", "WECHAT_OFFICIAL_ACCOUNT").enabled());
        template.setPagePath("pages/detail?id={targetId}");
        assertDoesNotThrow(() -> new WxNotificationTemplateRegistry(properties));
        template.setPagePath("https://untrusted.example/path");
        assertThrows(IllegalStateException.class, () -> new WxNotificationTemplateRegistry(properties));
    }

    @Test
    void hiddenTemplateStillSendsAndSharedProviderIdDoesNotCauseDuplicateType() {
        var properties = new WxMpNoticeTemplateProperties();
        var reply = enabledTemplate("reply");
        reply.setSubscribeVisible(false);
        properties.setNoticeTemplates(Map.of("reply", reply, "comment", enabledTemplate("comment_reply")));
        var registry = new WxNotificationTemplateRegistry(properties);
        assertTrue(registry.resolve("reply", "WECHAT_OFFICIAL_ACCOUNT").enabled());
        assertNull(registry.resolve("system_notice", "WECHAT_OFFICIAL_ACCOUNT"));
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
        assertThrows(IllegalStateException.class, () -> new WxNotificationTemplateRegistry(properties));
        field.setDefaultValue(null);
        field.setFormatter("datetime");
        field.setMaxLength(5);
        assertThrows(IllegalStateException.class, () -> new WxNotificationTemplateRegistry(properties));
    }

    @Test
    void repositoryYamlBindsAndAllEnabledMappingsRenderFrozenContent() throws Exception {
        MockEnvironment environment = new MockEnvironment();
        new YamlPropertySourceLoader().load("repository", new ClassPathResource("application.yaml"))
                .forEach(source -> environment.getPropertySources().addLast(source));
        var properties = Binder.get(environment).bind("wx.mp", Bindable.of(WxMpNoticeTemplateProperties.class)).get();
        var registry = new WxNotificationTemplateRegistry(properties);
        var renderer = new WxNotificationTemplateRenderer();
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
            assertTrue(registry.resolve(type.getKey(), "WECHAT_OFFICIAL_ACCOUNT").enabled());
            assertFalse(renderer.renderData(registry.resolve(type.getKey(), "WECHAT_OFFICIAL_ACCOUNT"), frozen).isEmpty());
            assertFalse(renderer.resolvePath(registry.resolve(type.getKey(), "WECHAT_OFFICIAL_ACCOUNT").pagePath(), frozen).contains("{"));
        }
    }

    private WxMpNoticeTemplateProperties.Template enabledTemplate(String type) {
        var template = template(type, true);
        template.setTemplateId("synthetic-template");
        var field = new WxMpNoticeTemplateProperties.Field();
        field.setSource("title");
        template.setFields(new java.util.LinkedHashMap<>(Map.of("thing1", field)));
        return template;
    }
}
