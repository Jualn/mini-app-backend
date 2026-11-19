package cn.jualn.miniapp.module.authorization.service;

import cn.jualn.miniapp.module.authorization.model.UnifiedModels;

import java.util.Map;
import java.util.Set;

/**
 * Fixed owning policies for the existing global business directories and module operations.
 * Service owners still resolve the real resource namespace and require its exact action;
 * this boundary proves the single business PLATFORM source before a capability is projected.
 * No permission alias, organization membership or RBAC GLOBAL scope can substitute for it.
 */
public final class PlatformDutyPolicy {
    private PlatformDutyPolicy() {
    }

    private record Rule(String role, String namespace, Set<String> permissions) {
    }

    private static final Map<String, Rule> RULES = Map.ofEntries(
            Map.entry("HOME_BANNER_MANAGE", new Rule("HOME_BANNER_MANAGER_V1", "HOME", Set.of("home-banner:manage"))),
            Map.entry("POINTS_ACCEPTANCE_MANAGE", new Rule("POINTS_ACCEPTANCE_STEWARD_V1", "POINTS", Set.of("points:acceptance-manage"))),
            Map.entry("ARTICLE_CATEGORY_MAINTAIN", new Rule("ARTICLE_CATEGORY_STEWARD_V1", "ARTICLE", Set.of("article-category:manage", "article-category:read"))),
            Map.entry("POST_DICTIONARY_MAINTAIN", new Rule("POST_DICTIONARY_STEWARD_V1", "POST", Set.of("post-dictionary:manage", "post-dictionary:read"))),
            Map.entry("POST_MODERATE", new Rule("POST_MODERATOR_V1", "POST", Set.of("comment:dispose", "content:read", "post:curate", "post:dispose"))),
            Map.entry("OFFICIAL_CONTENT_EDIT", new Rule("CONTENT_EDITOR", "OFFICIAL_CONTENT", Set.of("content:read", "media:read", "media:write", "official-content:edit", "official-content:read"))),
            Map.entry("OFFICIAL_CONTENT_PUBLISH", new Rule("CONTENT_PUBLISHER", "OFFICIAL_CONTENT", Set.of("media:read", "official-content:publish", "official-content:read", "official-content:unpublish"))),
            Map.entry("ACTIVITY_MANAGE", new Rule("EVENT_OPERATOR", "ACTIVITY", Set.of("activity:edit", "activity:lifecycle", "activity:publish", "activity:read", "activity:unpublish", "media:read", "media:write"))),
            Map.entry("PUBLIC_EVENT_MANAGE", new Rule("EVENT_OPERATOR", "PUBLIC_EVENT", Set.of("media:read", "media:write", "public-event:edit", "public-event:lifecycle", "public-event:publish", "public-event:read", "public-event:unpublish"))),
            Map.entry("MENTOR_MANAGE", new Rule("MENTOR_REGISTRAR", "MENTOR", Set.of("mentor:qualification-manage"))),
            Map.entry("ACTIVITY_REGISTRATION_DISPOSE", new Rule("REGISTRATION_DISPOSER_V1", "ACTIVITY", Set.of("activity:registration:read", "activity:registration:dispose"))),
            Map.entry("ACTIVITY_REGISTRATION_READ", new Rule("REGISTRATION_READER", "ACTIVITY", Set.of("activity:registration:read"))),
            Map.entry("ACTIVITY_REGISTRATION_EXPORT", new Rule("REGISTRATION_EXPORTER", "ACTIVITY", Set.of("activity:registration:export", "activity:registration:read"))),
            Map.entry("USER_ACCOUNT_GOVERN", new Rule("USER_GOVERNOR", "USER", Set.of("user:manage", "user:read"))),
            Map.entry("REPORT_HANDLE", new Rule("REPORT_HANDLER", "REPORT", Set.of("report:handle", "report:read"))),
            Map.entry("NOTICE_MONITOR", new Rule("NOTICE_MONITOR", "NOTIFICATION", Set.of("notice:read"))),
            Map.entry("SYSTEM_MONITOR", new Rule("NOTICE_MONITOR", "SYSTEM", Set.of("system:read"))),
            Map.entry("POST_SAFETY_REVIEW", new Rule("CONTENT_REVIEWER", "POST", Set.of("review:decide", "review:read"))),
            Map.entry("ACTIVITY_SAFETY_REVIEW", new Rule("CONTENT_REVIEWER", "ACTIVITY", Set.of("review:decide", "review:read"))),
            Map.entry("PUBLIC_EVENT_COMMENT_REVIEW", new Rule("CONTENT_REVIEWER", "PUBLIC_EVENT", Set.of("review:decide", "review:read"))),
            Map.entry("ACTIVITY_COMMENT_MODERATE", new Rule("CONTENT_OPERATOR", "ACTIVITY", Set.of("comment:dispose", "content:read"))),
            Map.entry("PUBLIC_EVENT_COMMENT_MODERATE", new Rule("CONTENT_OPERATOR", "PUBLIC_EVENT", Set.of("comment:dispose", "content:read"))));

    public static boolean supports(String code) {
        return RULES.containsKey(code);
    }

    public static boolean matches(UnifiedDutyCatalog.Template template, UnifiedModels.Selection selection) {
        var rule = RULES.get(template.dutyCode());
        var target = selection.target();
        return rule != null && rule.role().equals(template.roleCode()) && rule.namespace().equals(template.namespace())
                && rule.permissions().equals(Set.copyOf(template.permissions())) && target != null && template.targetTypes().contains(target.type())
                && ("PLATFORM".equals(target.type()) && target.personId() == null && target.organizationId() == null
                    || Set.of("POST_MODERATE","POST_SAFETY_REVIEW","REPORT_HANDLE").contains(template.dutyCode()) && "ORGANIZATION".equals(target.type()) && target.personId()==null && target.organizationId()!=null)
                && selection.audienceTargets() == null;
    }
}
