package cn.jualn.miniapp.module.authorization.model;

import java.util.Map;
import java.util.Set;

/** Fixed operation families. Family membership never supplies permissions or target coverage. */
public final class DutyOperations {
    private DutyOperations() { }
    private static final Map<String, String> FAMILIES = Map.ofEntries(
            Map.entry("ACTIVITY_READ", "ACTIVITY_MANAGE"),
            Map.entry("ACTIVITY_EDIT", "ACTIVITY_MANAGE"),
            Map.entry("ACTIVITY_PUBLICATION_MANAGE", "ACTIVITY_MANAGE"),
            Map.entry("ACTIVITY_LIFECYCLE_MANAGE", "ACTIVITY_MANAGE"),
            Map.entry("PUBLIC_EVENT_READ", "PUBLIC_EVENT_MANAGE"),
            Map.entry("PUBLIC_EVENT_EDIT", "PUBLIC_EVENT_MANAGE"),
            Map.entry("PUBLIC_EVENT_PUBLICATION_MANAGE", "PUBLIC_EVENT_MANAGE"),
            Map.entry("PUBLIC_EVENT_LIFECYCLE_MANAGE", "PUBLIC_EVENT_MANAGE"),
            Map.entry("POST_READ", "POST_MODERATE"),
            Map.entry("POST_CURATE", "POST_MODERATE"),
            Map.entry("POST_DISPOSE", "POST_MODERATE"),
            Map.entry("POST_COMMENT_MODERATE", "POST_MODERATE"),
            Map.entry("OFFICIAL_CONTENT_READ", "OFFICIAL_CONTENT_EDIT"),
            Map.entry("ARTICLE_READ", "ARTICLE_EDIT"),
            Map.entry("ARTICLE_CATEGORY_READ", "ARTICLE_CATEGORY_MAINTAIN"),
            Map.entry("POST_DICTIONARY_READ", "POST_DICTIONARY_MAINTAIN"),
            Map.entry("USER_ACCOUNT_READ", "USER_ACCOUNT_GOVERN"),
            Map.entry("LEARNING_READ", "LEARNING_MANAGE"),
            Map.entry("LEARNING_EDIT", "LEARNING_MANAGE"),
            Map.entry("LEARNING_AUDIENCE_MANAGE", "LEARNING_MANAGE"),
            Map.entry("LEARNING_PUBLISH", "LEARNING_MANAGE"),
            Map.entry("LEARNING_LIFECYCLE_MANAGE", "LEARNING_MANAGE"),
            Map.entry("LEARNING_ASSIGNMENT_REVOKE", "LEARNING_MANAGE"),
            Map.entry("LEARNING_SUBMISSION_READ", "LEARNING_REVIEW"),
            Map.entry("REPORT_READ", "REPORT_HANDLE"),
            Map.entry("REPORT_RESOLVE", "REPORT_HANDLE"),
            Map.entry("POST_SAFETY_READ", "POST_SAFETY_REVIEW"),
            Map.entry("ACTIVITY_SAFETY_READ", "ACTIVITY_SAFETY_REVIEW"),
            Map.entry("PUBLIC_EVENT_COMMENT_REVIEW_READ", "PUBLIC_EVENT_COMMENT_REVIEW"));
    public static boolean matches(String requestedFamily, String actualDuty) {
        return requestedFamily != null && actualDuty != null && (requestedFamily.equals(actualDuty)
                || requestedFamily.equals(FAMILIES.get(actualDuty)));
    }
    public static boolean learning(String code) {
        return matches("LEARNING_MANAGE", code);
    }
    public static boolean audience(String code) {
        return code != null && Set.of("LEARNING_MANAGE", "LEARNING_EDIT", "LEARNING_AUDIENCE_MANAGE", "LEARNING_PUBLISH").contains(code);
    }
    public static boolean growth(String code) {
        return matches("POST_MODERATE", code) || matches("POST_SAFETY_REVIEW", code)
                || matches("REPORT_HANDLE", code);
    }
}
