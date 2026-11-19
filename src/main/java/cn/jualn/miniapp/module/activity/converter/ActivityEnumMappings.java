package cn.jualn.miniapp.module.activity.converter;

import cn.jualn.miniapp.module.activity.enums.ActivityCategory;
import cn.jualn.miniapp.module.activity.enums.ActivityStatus;

/**
 * Pure conversion of activity-owned codes; contains no business decisions or persistence access.
 */
public final class ActivityEnumMappings {
    private ActivityEnumMappings() {
    }

    public static ActivityCategory toActivityCategory(Integer code) {
        if (code == null) return null;
        return ActivityCategory.fromCode(code);
    }

    public static Integer fromActivityCategory(ActivityCategory category) {
        return category == null ? null : category.getCode();
    }

    public static ActivityStatus toActivityStatus(Integer code) {
        if (code == null) return null;
        return ActivityStatus.fromCode(code);
    }

    public static Integer fromActivityStatus(ActivityStatus status) {
        return status == null ? null : status.getCode();
    }
}
