package cn.jualn.miniapp.module.authorization.service;

import cn.jualn.miniapp.module.authorization.model.DutyOperations;

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
            Map.entry("REPORT_RESOLVE", new Rule("REPORT_RESOLVER_V1", "REPORT", Set.of("report:read", "report:handle", "report:target-dispose"))),
            Map.entry("ACTIVITY_READ", new Rule("ACTIVITY_READ_V1", "ACTIVITY", Set.of("activity:read", "media:read"))),
            Map.entry("ACTIVITY_EDIT", new Rule("ACTIVITY_EDIT_V1", "ACTIVITY", Set.of("activity:edit", "activity:read", "media:read", "media:write"))),
            Map.entry("ACTIVITY_PUBLICATION_MANAGE", new Rule("ACTIVITY_PUBLICATION_MANAGE_V1", "ACTIVITY", Set.of("activity:publish", "activity:read", "activity:unpublish", "media:read"))),
            Map.entry("ACTIVITY_LIFECYCLE_MANAGE", new Rule("ACTIVITY_LIFECYCLE_MANAGE_V1", "ACTIVITY", Set.of("activity:lifecycle", "activity:read", "media:read"))),
            Map.entry("PUBLIC_EVENT_READ", new Rule("PUBLIC_EVENT_READ_V1", "PUBLIC_EVENT", Set.of("media:read", "public-event:read"))),
            Map.entry("PUBLIC_EVENT_EDIT", new Rule("PUBLIC_EVENT_EDIT_V1", "PUBLIC_EVENT", Set.of("media:read", "media:write", "public-event:edit", "public-event:read"))),
            Map.entry("PUBLIC_EVENT_PUBLICATION_MANAGE", new Rule("PUBLIC_EVENT_PUBLICATION_MANAGE_V1", "PUBLIC_EVENT", Set.of("media:read", "public-event:publish", "public-event:read", "public-event:unpublish"))),
            Map.entry("PUBLIC_EVENT_LIFECYCLE_MANAGE", new Rule("PUBLIC_EVENT_LIFECYCLE_MANAGE_V1", "PUBLIC_EVENT", Set.of("media:read", "public-event:lifecycle", "public-event:read"))),
            Map.entry("POST_READ", new Rule("POST_READ_V1", "POST", Set.of("content:read"))),
            Map.entry("POST_CURATE", new Rule("POST_CURATE_V1", "POST", Set.of("content:read", "post:curate"))),
            Map.entry("POST_DISPOSE", new Rule("POST_DISPOSE_V1", "POST", Set.of("content:read", "post:dispose"))),
            Map.entry("POST_COMMENT_MODERATE", new Rule("POST_COMMENT_MODERATE_V1", "POST", Set.of("comment:dispose", "content:read"))),
            Map.entry("OFFICIAL_CONTENT_READ", new Rule("OFFICIAL_CONTENT_READ_V1", "OFFICIAL_CONTENT", Set.of("content:read", "media:read", "official-content:read"))),
            Map.entry("ARTICLE_CATEGORY_READ", new Rule("ARTICLE_CATEGORY_READ_V1", "ARTICLE", Set.of("article-category:read"))),
            Map.entry("POST_DICTIONARY_READ", new Rule("POST_DICTIONARY_READ_V1", "POST", Set.of("post-dictionary:read"))),
            Map.entry("USER_ACCOUNT_READ", new Rule("USER_ACCOUNT_READ_V1", "USER", Set.of("user:read"))),
            Map.entry("REPORT_READ", new Rule("REPORT_READ_V1", "REPORT", Set.of("report:read"))),
            Map.entry("POST_SAFETY_READ", new Rule("POST_SAFETY_READ_V1", "POST", Set.of("review:read"))),
            Map.entry("ACTIVITY_SAFETY_READ", new Rule("ACTIVITY_SAFETY_READ_V1", "ACTIVITY", Set.of("review:read"))),
            Map.entry("PUBLIC_EVENT_COMMENT_REVIEW_READ", new Rule("PUBLIC_EVENT_COMMENT_REVIEW_READ_V1", "PUBLIC_EVENT", Set.of("review:read"))),
            Map.entry("PARTY_PERSONNEL_BINDING_MANAGE", new Rule("PARTY_PERSONNEL_BINDING_MANAGER_V1", "ORGANIZATION", Set.of("party-personnel:binding-manage"))),
            Map.entry("PARTY_PERSONNEL_ROSTER_VERIFY", new Rule("PARTY_PERSONNEL_ROSTER_VERIFIER_V1", "ORGANIZATION", Set.of("party-personnel:roster-verify"))),
            Map.entry("PARTY_PERSONNEL_REMOVE", new Rule("PARTY_PERSONNEL_REMOVER_V1", "ORGANIZATION", Set.of("party-personnel:remove"))),
            Map.entry("PARTY_PERSONNEL_PROFILE_MAINTAIN", new Rule("PARTY_PERSONNEL_PROFILE_EDITOR_V1", "ORGANIZATION", Set.of("party-personnel:profile-maintain"))),
            Map.entry("PARTY_PERSONNEL_ADD", new Rule("PARTY_PERSONNEL_ADDER_V1", "ORGANIZATION", Set.of("party-personnel:add"))),
            Map.entry("PARTY_PERSONNEL_READ", new Rule("PARTY_PERSONNEL_READER_V1", "ORGANIZATION", Set.of("party-personnel:read"))),
            Map.entry("HOME_BANNER_MANAGE", new Rule("HOME_BANNER_MANAGER_V1", "HOME", Set.of("home-banner:manage"))),
            Map.entry("POINTS_ACCEPTANCE_MANAGE", new Rule("POINTS_ACCEPTANCE_STEWARD_V1", "POINTS", Set.of("points:acceptance-manage"))),
            Map.entry("ARTICLE_CATEGORY_MAINTAIN", new Rule("ARTICLE_CATEGORY_STEWARD_V1", "ARTICLE", Set.of("article-category:manage", "article-category:read"))),
            Map.entry("POST_DICTIONARY_MAINTAIN", new Rule("POST_DICTIONARY_STEWARD_V1", "POST", Set.of("post-dictionary:manage", "post-dictionary:read"))),
            Map.entry("POST_MODERATE", new Rule("POST_MODERATOR_V1", "POST", Set.of("comment:dispose", "content:read", "post:curate", "post:dispose"))),
            Map.entry("OFFICIAL_CONTENT_EDIT", new Rule("CONTENT_EDITOR", "OFFICIAL_CONTENT", Set.of("content:read", "media:read", "media:write", "official-content:edit", "official-content:read"))),
            Map.entry("OFFICIAL_CONTENT_PUBLISH", new Rule("CONTENT_PUBLISHER", "OFFICIAL_CONTENT", Set.of("media:read", "official-content:publish", "official-content:read", "official-content:unpublish"))),
            Map.entry("ACTIVITY_MANAGE", new Rule("EVENT_OPERATOR", "ACTIVITY", Set.of("activity:edit", "activity:lifecycle", "activity:publish", "activity:read", "activity:unpublish", "media:read", "media:write"))),
            Map.entry("PUBLIC_EVENT_MANAGE", new Rule("EVENT_OPERATOR", "PUBLIC_EVENT", Set.of("media:read", "media:write", "public-event:edit", "public-event:lifecycle", "public-event:publish", "public-event:read", "public-event:unpublish"))),
            Map.entry("MENTOR_READ", new Rule("MENTOR_READER_V1", "MENTOR", Set.of("mentor:read"))),
            Map.entry("MENTOR_CREATE", new Rule("MENTOR_CREATOR_V1", "MENTOR", Set.of("mentor:create"))),
            Map.entry("MENTOR_PROFILE_MAINTAIN", new Rule("MENTOR_PROFILE_EDITOR_V1", "MENTOR", Set.of("mentor:profile-maintain"))),
            Map.entry("MENTOR_QUALIFICATION_MANAGE", new Rule("MENTOR_QUALIFICATION_MANAGER_V1", "MENTOR", Set.of("mentor:qualification-decide"))),
            Map.entry("MENTOR_BINDING_MANAGE", new Rule("MENTOR_BINDING_MANAGER_V1", "MENTOR", Set.of("mentor:binding-manage"))),
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
                    || template.dutyCode().startsWith("MENTOR_") && !Set.of("MENTOR_MANAGE", "MENTOR_CREATE").contains(template.dutyCode()) && "PERSON".equals(target.type()) && target.personId() != null && target.organizationId() == null
                    || template.dutyCode().startsWith("PARTY_PERSONNEL_") && "ORGANIZATION".equals(target.type()) && target.personId()==null && target.organizationId()!=null
                    || DutyOperations.growth(template.dutyCode()) && "ORGANIZATION".equals(target.type()) && target.personId()==null && target.organizationId()!=null)
                && selection.audienceTargets() == null;
    }
}
