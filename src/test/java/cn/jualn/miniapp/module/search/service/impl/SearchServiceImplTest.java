package cn.jualn.miniapp.module.search.service.impl;

import cn.jualn.miniapp.common.constant.RedisKeyConstant;
import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.module.activity.enums.ActivityStatus;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.result.PageResult;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.module.audit.enums.AuditStatus;
import cn.jualn.miniapp.module.post.bo.PostListBO;
import cn.jualn.miniapp.module.post.bo.PostSearchBO;
import cn.jualn.miniapp.module.post.enums.PostStatus;
import cn.jualn.miniapp.module.post.service.PostQueries;
import cn.jualn.miniapp.module.search.bo.SearchPageBO;
import cn.jualn.miniapp.module.search.bo.SearchResultBO;
import cn.jualn.miniapp.module.search.bo.SearchTabCountBO;
import cn.jualn.miniapp.module.search.dto.request.SearchQuery;
import cn.jualn.miniapp.module.search.mapper.SearchDocMapper;
import cn.jualn.miniapp.module.activity.bo.ActivitySearchBO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SearchServiceImplTest {
    @AfterEach
    void clearContext() {
        UserContext.clear();
    }

    @Mock
    private SearchDocMapper searchDocMapper;
    @Mock
    private RedisService redisService;
    @Mock
    private PostQueries postService;

    @Test
    void searchPosts_shouldDelegateAndRecordHotKeyword() {
        UserContext.setUserId(7L);
        when(redisService.setIfAbsent(RedisKeyConstant.searchHotDedup(7L, "java"),
                "1", RedisKeyConstant.SEARCH_HOT_DEDUP_TTL)).thenReturn(true);
        SearchServiceImpl service = new SearchServiceImpl(searchDocMapper, redisService, postService);
        PageResult<PostListBO> pageResult = PageResult.of(List.of(
                PostListBO.builder().id(10L).build()
        ), false);
        pageResult.setNextCursor(10L);
        when(postService.searchPosts("java", 99L, 1)).thenReturn(pageResult);

        SearchQuery query = new SearchQuery();
        query.setKeyword("  java  ");
        query.setPageSize(1);
        query.setLastId(99L);

        var result = service.searchPosts(query);

        assertEquals(10L, result.getNextCursor());
        verify(redisService).zIncrementScore(RedisKeyConstant.SEARCH_HOT_ZSET, "java", 1D);
        verify(postService).searchPosts("java", 99L, 1);
    }

    @Test
    void pageSearch_shouldReturnHasMoreAndNextCursor() {
        SearchServiceImpl service = new SearchServiceImpl(searchDocMapper, redisService, postService);
        when(searchDocMapper.selectSearchResults(
                any(), any(), any(), any(), any()
        )).thenReturn(List.of(
                SearchResultBO.builder().targetType(TargetType.POST).targetId(20L).build(),
                SearchResultBO.builder().targetType(TargetType.POST).targetId(10L).build()
        ));

        SearchPageBO query = new SearchPageBO();
        query.setKeyword("  java  ");
        query.setTargetType(TargetType.POST);
        query.setPageSize(1);
        query.setLastPublishedAt(LocalDateTime.now());
        query.setLastId(99L);

        var result = service.pageSearch(query);

        assertEquals(20L, result.getNextCursor());
        assertEquals(Boolean.TRUE, result.getHasMore());
        verify(searchDocMapper).selectSearchResults("java", TargetType.POST.getCode(), query.getLastPublishedAt(), 99L, 2);
    }

    @Test
    void countSearchTabs_shouldReadCurrentVisibilityAndFillMissingTabs() {
        SearchServiceImpl service = new SearchServiceImpl(searchDocMapper, redisService, postService);
        when(searchDocMapper.selectTabCounts("java")).thenReturn(List.of(
                SearchTabCountBO.builder().targetType(TargetType.POST).count(12L).build()
        ));

        List<SearchTabCountBO> result = service.countSearchTabs(" java ");

        assertEquals(2, result.size());
        assertEquals(12L, result.get(0).getCount());
        assertEquals(0L, result.get(1).getCount());
        org.mockito.Mockito.verifyNoInteractions(redisService);
    }

    @Test
    void syncPost_shouldUpsertOnlyWhenPostIsPublishedAndPassed() {
        SearchServiceImpl service = new SearchServiceImpl(searchDocMapper, redisService, postService);
        PostSearchBO post = PostSearchBO.builder()
                .id(5L)
                .status(PostStatus.PUBLISHED)
                .auditStatus(AuditStatus.PASS)
                .content("hello world")
                .publishedAt(LocalDateTime.of(2026, 5, 31, 10, 0))
                .build();

        service.syncPost(post);

        verify(searchDocMapper).upsert(TargetType.POST.getCode(), 5L, "hello world", 1, post.getPublishedAt());
    }

    @Test
    void syncActivity_shouldHideUnpublishedActivity() {
        SearchServiceImpl service = new SearchServiceImpl(searchDocMapper, redisService, postService);
        ActivitySearchBO activity = ActivitySearchBO.builder()
                .id(9L)
                .publishStatus(0)
                .title("activity")
                .summary("content")
                .publishedAt(LocalDateTime.of(2026, 5, 31, 10, 0))
                .build();

        service.syncActivity(activity);

        verify(searchDocMapper).upsert(TargetType.ACTIVITY.getCode(), 9L, "activity content", 0, activity.getPublishedAt());
    }
}



