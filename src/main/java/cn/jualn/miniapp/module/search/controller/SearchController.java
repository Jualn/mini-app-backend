package cn.jualn.miniapp.module.search.controller;

import cn.jualn.miniapp.common.result.PageResult;
import cn.jualn.miniapp.common.result.Result;
import cn.jualn.miniapp.module.activity.bo.ActivityListBO;
import cn.jualn.miniapp.module.post.bo.PostListBO;
import cn.jualn.miniapp.module.search.bo.SearchPageBO;
import cn.jualn.miniapp.module.search.bo.SearchResultBO;
import cn.jualn.miniapp.module.search.bo.SearchTabCountBO;
import cn.jualn.miniapp.module.search.converter.SearchConverter;
import cn.jualn.miniapp.module.search.dto.request.SearchPageQuery;
import cn.jualn.miniapp.module.search.dto.request.SearchQuery;
import cn.jualn.miniapp.module.search.service.SearchService;
import cn.jualn.miniapp.module.search.vo.SearchResultVO;
import cn.jualn.miniapp.module.search.vo.SearchTabCountVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Validated
@RestController
@RequestMapping("/v1/search")
@RequiredArgsConstructor
public class SearchController {

    private final SearchService searchService;
    private final SearchConverter searchConverter;

    @GetMapping("/posts")
    public Result<PageResult<PostListBO>> searchPosts(@Valid SearchQuery query) {
        return Result.ok(searchService.searchPosts(query));
    }

    @GetMapping("/activities")
    public Result<PageResult<ActivityListBO>> searchActivities(@Valid SearchQuery query) {
        return Result.ok(searchService.searchActivities(query));
    }

    @GetMapping
    public Result<PageResult<SearchResultVO>> pageSearch(@Valid SearchPageQuery query) {
        SearchPageBO pageBO = searchConverter.toPageBO(query);
        PageResult<SearchResultBO> pageResult = searchService.pageSearch(pageBO);
        return Result.ok(PageResult.of(
                searchConverter.toVOList(pageResult.getList()),
                pageResult.getHasMore(),
                pageResult.getNextCursor()
        ));
    }

    @GetMapping("/count")
    public Result<List<SearchTabCountVO>> countSearchTabs(@Valid SearchPageQuery query) {
        List<SearchTabCountBO> counts = searchService.countSearchTabs(query.getKeyword());
        return Result.ok(searchConverter.toTabVOList(counts));
    }
}

