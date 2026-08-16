package cn.jualn.miniapp.module.search.service.impl;

import cn.jualn.miniapp.common.constant.RedisKeyConstant;
import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.common.enums.ActivityStatus;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.PageResult;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.module.activity.bo.ActivitySearchBO;
import cn.jualn.miniapp.module.activity.bo.ActivityListBO;
import cn.jualn.miniapp.module.activity.service.ActivityService;
import cn.jualn.miniapp.module.post.bo.PostListBO;
import cn.jualn.miniapp.module.post.bo.PostSearchBO;
import cn.jualn.miniapp.common.enums.PostStatus;
import cn.jualn.miniapp.module.post.service.PostService;
import cn.jualn.miniapp.module.search.bo.SearchPageBO;
import cn.jualn.miniapp.module.search.bo.SearchResultBO;
import cn.jualn.miniapp.module.search.bo.SearchTabCountBO;
import cn.jualn.miniapp.module.search.dto.request.SearchQuery;
import cn.jualn.miniapp.module.search.mapper.SearchDocMapper;
import cn.jualn.miniapp.module.search.service.SearchService;
import cn.jualn.miniapp.module.audit.enums.AuditStatus;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class SearchServiceImpl implements SearchService {

    private static final Set<TargetType> ALLOWED_TARGET_TYPES =
            Set.of(TargetType.POST, TargetType.ACTIVITY);
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 50;

    private final SearchDocMapper searchDocMapper;
    private final RedisService redisService;
    private final PostService postService;
    private final ActivityService activityService;

    @Override
    public PageResult<PostListBO> searchPosts(SearchQuery query) {
        SearchQuery actualQuery = query == null ? new SearchQuery() : query;
        String keyword = normalizeKeyword(actualQuery.getKeyword());
        recordHotKeyword(keyword);
        return postService.searchPosts(keyword, actualQuery.getLastId(), actualQuery.getPageSize());
    }

    @Override
    public PageResult<ActivityListBO> searchActivities(SearchQuery query) {
        SearchQuery actualQuery = query == null ? new SearchQuery() : query;
        String keyword = normalizeKeyword(actualQuery.getKeyword());
        recordHotKeyword(keyword);
        return activityService.searchActivities(keyword, actualQuery.getLastId(), actualQuery.getPageSize());
    }


    @Override
    @Deprecated
    public PageResult<SearchResultBO> pageSearch(SearchPageBO query) {
        SearchPageBO actualQuery = query == null ? new SearchPageBO() : query;
        String keyword = normalizeKeyword(actualQuery.getKeyword());
        if (!StringUtils.hasText(keyword)) {
            throw new BusinessException(ResultCode.SEARCH_PARAM_INVALID, "keyword 不能为空");
        }
        assertTargetType(actualQuery.getTargetType());
        int pageSize = normalizePageSize(actualQuery.getPageSize());
        validateCursor(actualQuery.getLastPublishedAt(), actualQuery.getLastId());

        List<SearchResultBO> results = searchDocMapper.selectSearchResults(
                keyword,
                actualQuery.getTargetType().getCode(),
                actualQuery.getLastPublishedAt(),
                actualQuery.getLastId(),
                pageSize + 1
        );
        boolean hasMore = results.size() > pageSize;
        if (hasMore) {
            results = results.subList(0, pageSize);
        }

        return PageResult.of(results, hasMore,
                results.isEmpty() ? null : results.get(results.size() - 1).getTargetId());
    }

    @Override
    @Deprecated
    public List<SearchTabCountBO> countSearchTabs(String keyword) {
        String normalizedKeyword = normalizeKeyword(keyword);
        if (!StringUtils.hasText(normalizedKeyword)) {
            throw new BusinessException(ResultCode.SEARCH_PARAM_INVALID, "keyword 不能为空");
        }

        String cacheKey = RedisKeyConstant.searchCount(md5(normalizedKeyword));
        List<SearchTabCountBO> cached = redisService.get(cacheKey, new TypeReference<>() {
        });
        if (cached != null) {
            return normalizeTabs(cached);
        }

        List<SearchTabCountBO> counts = normalizeTabs(searchDocMapper.selectTabCounts(normalizedKeyword));
        redisService.set(cacheKey, counts, RedisKeyConstant.SEARCH_COUNT_TTL);
        return counts;
    }

    @Override
    @Deprecated
    public void syncPost(PostSearchBO post) {
        if (post == null || post.getId() == null) {
            return;
        }

        int status = isSearchablePost(post.getStatus(), post.getAuditStatus()) ? 1 : 0;
        searchDocMapper.upsert(
                TargetType.POST.getCode(),
                post.getId(),
                buildPostSearchText(post.getContent()),
                status,
                normalizePublishedAt(post.getPublishedAt())
        );
    }

    @Override
    @Deprecated
    public void syncActivity(ActivitySearchBO activity) {
        if (activity == null || activity.getId() == null) {
            return;
        }
        int status = isSearchableActivity(activity.getStatus()) ? 1 : 0;
        searchDocMapper.upsert(
                TargetType.ACTIVITY.getCode(),
                activity.getId(),
                buildActivitySearchText(activity),
                status,
                normalizePublishedAt(activity.getPublishedAt())
        );
    }

    @Override
    @Deprecated
    public void removeByTarget(TargetType targetType, Long targetId) {
        if (targetType == null || targetId == null) {
            return;
        }
        searchDocMapper.deleteByTarget(targetType.getCode(), targetId);
    }

    private void assertTargetType(TargetType targetType) {
        if (!ALLOWED_TARGET_TYPES.contains(targetType)) {
            throw new BusinessException(ResultCode.SEARCH_PARAM_INVALID, "仅支持帖子和活动搜索");

        }
    }

    private int normalizePageSize(Integer pageSize) {
        if (pageSize == null || pageSize <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }

    private void validateCursor(LocalDateTime lastPublishedAt, Long lastId) {
        if ((lastPublishedAt == null) != (lastId == null)) {
            throw new BusinessException(ResultCode.SEARCH_PARAM_INVALID, "lastPublishedAt 和 lastId 需同时提供");
        }
    }

    private String normalizeKeyword(String keyword) {
        if (keyword == null) {
            return null;
        }
        return keyword.replaceAll("[+\\-><()~*\"@]", " ").trim();
    }

    private void recordHotKeyword(String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return;
        }

        if (UserContext.getUserId() == null) {
            return; // 无法区分用户，无法去重，放弃记录
        }

        String dedupKey = RedisKeyConstant.searchHotDedup(UserContext.getUserId(), keyword);
        boolean isNew = redisService.setIfAbsent(dedupKey, "1", RedisKeyConstant.SEARCH_HOT_DEDUP_TTL);
        if (isNew) {
            redisService.zIncrementScore(RedisKeyConstant.SEARCH_HOT_ZSET, keyword, 1D);
            redisService.zRemoveRange(RedisKeyConstant.SEARCH_HOT_ZSET, 0, -101);
        }
    }

    private List<SearchTabCountBO> normalizeTabs(List<SearchTabCountBO> raw) {
        Map<Integer, SearchTabCountBO> countMap = new LinkedHashMap<>();
        if (raw != null) {
            for (SearchTabCountBO item : raw) {
                if (item != null && item.getTargetType() != null) {
                    countMap.put(item.getTargetType().getCode(), item);
                }
            }
        }

        List<SearchTabCountBO> result = new ArrayList<>();
        result.add(SearchTabCountBO.builder()
                .targetType(TargetType.POST)
                .count(countMap.getOrDefault(TargetType.POST.getCode(), SearchTabCountBO.builder()
                        .targetType(TargetType.POST)
                        .count(0L)
                        .build()).getCount())
                .build());
        result.add(SearchTabCountBO.builder()
                .targetType(TargetType.ACTIVITY)
                .count(countMap.getOrDefault(TargetType.ACTIVITY.getCode(), SearchTabCountBO.builder()
                        .targetType(TargetType.ACTIVITY)
                        .count(0L)
                        .build()).getCount())
                .build());
        return result;
    }

    private boolean isSearchablePost(PostStatus postStatus, AuditStatus auditStatus) {
        return Objects.equals(postStatus, PostStatus.PUBLISHED)
                && Objects.equals(auditStatus, AuditStatus.PASS);
    }

    private boolean isSearchableActivity(ActivityStatus status) {
        return status != null && (Objects.equals(status, ActivityStatus.SIGNUP)
                || Objects.equals(status, ActivityStatus.ONGOING)
                || Objects.equals(status, ActivityStatus.ENDED)
                || Objects.equals(status, ActivityStatus.DRAFT));
    }

    private String buildPostSearchText(String content) {
        return left(content, 300);
    }

    private String buildActivitySearchText(ActivitySearchBO activity) {
        String location = StringUtils.hasText(activity.getLocation()) ? activity.getLocation().trim() : "";
        String content = left(activity.getContent(), 300);
        StringBuilder builder = new StringBuilder();
        if (StringUtils.hasText(activity.getTitle())) {
            builder.append(activity.getTitle().trim());
        }
        if (StringUtils.hasText(location)) {
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(location);
        }
        if (StringUtils.hasText(content)) {
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(content);
        }
        return builder.toString();
    }

    private String left(String value, int maxLen) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() <= maxLen ? trimmed : trimmed.substring(0, maxLen);
    }

    private LocalDateTime normalizePublishedAt(LocalDateTime publishedAt) {
        return publishedAt == null ? LocalDateTime.now() : publishedAt;
    }

    /**
     * MD5 加密，主要用于生成搜索统计的缓存 key
     * - 输入字符串统一转小写，避免大小写导致的缓存不命中
     * - 输出 16 进制字符串
     * - 若 MD5 不可用，则抛出 IllegalStateException，因这是环境问题，无法恢复
     * - 该方法不对输入进行过多校验，调用方需保证输入合理
     * 示例：输入 "Hello World" -> 输出 "b10a8db164e0754105b7a99be72e3fe5"
     *
     * @param value 输入字符串
     * @return MD5 16进制字符串
     * @throws IllegalStateException 如果 MD5 算法不可用
     */
    private String md5(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] bytes = digest.digest(value.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 不可用", e);
        }
    }
}


