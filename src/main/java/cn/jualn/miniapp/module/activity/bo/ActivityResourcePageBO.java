package cn.jualn.miniapp.module.activity.bo;

import java.util.List;

/** Public Activity cursor page independent of the legacy numeric cursor response. */
public record ActivityResourcePageBO(
        List<ActivityListBO> items,
        String nextCursor) {
}
