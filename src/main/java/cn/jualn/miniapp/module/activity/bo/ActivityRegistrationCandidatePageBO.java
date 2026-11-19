package cn.jualn.miniapp.module.activity.bo;

import java.util.List;

public record ActivityRegistrationCandidatePageBO(List<Item> items, int page, int pageSize, long totalItems) {
    public record Item(long activityId, String title, int publishStatus, int lifecycleStatus) { }
}
