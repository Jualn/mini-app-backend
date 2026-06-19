package cn.jualn.miniapp.module.search.service;

import cn.jualn.miniapp.common.result.PageResult;
import cn.jualn.miniapp.module.activity.bo.ActivitySearchBO;
import cn.jualn.miniapp.module.activity.bo.ActivityListBO;
import cn.jualn.miniapp.module.post.bo.PostListBO;
import cn.jualn.miniapp.module.post.bo.PostSearchBO;
import cn.jualn.miniapp.module.search.bo.SearchPageBO;
import cn.jualn.miniapp.module.search.bo.SearchResultBO;
import cn.jualn.miniapp.module.search.bo.SearchTabCountBO;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.module.search.dto.request.SearchQuery;

import java.util.List;

/**
 * 搜索服务。
 */
public interface SearchService {

    PageResult<PostListBO> searchPosts(SearchQuery query);

    PageResult<ActivityListBO> searchActivities(SearchQuery query);

    @Deprecated
    PageResult<SearchResultBO> pageSearch(SearchPageBO query);

    @Deprecated
    List<SearchTabCountBO> countSearchTabs(String keyword);

    @Deprecated
    void syncPost(PostSearchBO post);

    @Deprecated
    void syncActivity(ActivitySearchBO activity);

    @Deprecated
    void removeByTarget(TargetType targetType, Long targetId);
}

