package cn.jualn.miniapp.module.admin.auth.support;

import cn.jualn.miniapp.module.user.enums.UserRole;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Compatibility constants for old callers; management decisions belong to CurrentAuthorization.
 */
@Component
public class AdminPermissionPolicy {

    public static final String ALL = "*";
    public static final String POST_DICTIONARY_READ = "post-dictionary:read";
    public static final String POST_DICTIONARY_MANAGE = "post-dictionary:manage";
    public static final String CONTENT_READ = "content:read";
    public static final String CONTENT_MANAGE = "content:manage";
    public static final String REVIEW_READ = "review:read";
    public static final String REVIEW_DECIDE = "review:decide";
    public static final String REPORT_READ = "report:read";
    public static final String REPORT_HANDLE = "report:handle";
    public static final String ACTIVITY_READ = "activity:read";
    public static final String ACTIVITY_EDIT = "activity:edit";
    public static final String ACTIVITY_REGISTRATION_READ = "activity:registration:read";
    public static final String ACTIVITY_REGISTRATION_EXPORT = "activity:registration:export";
    public static final String PUBLIC_EVENT_READ = "public-event:read";
    public static final String PUBLIC_EVENT_EDIT = "public-event:edit";
    public static final String NOTICE_READ = "notice:read";
    public static final String USER_READ = "user:read";
    public static final String USER_MANAGE = "user:manage";
    public static final String SYSTEM_READ = "system:read";

    /** Historical scalar roles never authorize Admin access. */
    public List<String> permissions(UserRole role) { return List.of(); }
}
