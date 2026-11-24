package cn.jualn.miniapp.module.home.service;

import cn.jualn.miniapp.common.web.AdminQuery;
import cn.jualn.miniapp.common.web.StrongEtag;
import cn.jualn.miniapp.module.activity.service.ActivityQueries;
import cn.jualn.miniapp.module.article.service.ArticleService;
import cn.jualn.miniapp.module.authorization.service.CurrentAuthorization;
import cn.jualn.miniapp.module.home.mapper.HomeBannerMapper;
import cn.jualn.miniapp.module.home.mapper.HomeBannerMapper.Row;
import cn.jualn.miniapp.module.home.model.HomeContent.*;
import cn.jualn.miniapp.module.media.service.PublicImageReuse;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;

@Service
@RequiredArgsConstructor
public class HomeBannerService {
    private static final String RESOURCE = "home-banners";

    private final HomeBannerMapper banners;
    private final CurrentAuthorization authorization;
    private final ArticleService articles;
    private final ActivityQueries activities;
    private final PublicImageReuse images;

    public record Versioned(Configuration configuration, String etag) {}

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 5)
    public Versioned get(long actor) {
        authorize(actor);
        long revision = requireRevision(banners.revision());
        return versioned(banners.items(), revision);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public Versioned replace(long actor, String ifMatch, JsonNode body) {
        authorize(actor);
        long revision = requireRevision(banners.lockRevision());
        StrongEtag.requireStrict(ifMatch, RESOURCE, 1, revision);
        var requested = HomeBannerRequest.parse(body);
        var current = banners.items();

        var existing = new HashMap<Target, Row>();
        current.forEach(row -> existing.put(target(row.targetType(), row.targetId()), row));
        var newCovers = new TreeMap<Long, Integer>();
        for (int index = 0; index < requested.size(); index++) {
            Row row = requested.get(index);
            Row previous = existing.get(target(row.targetType(), row.targetId()));
            if ((previous == null || !previous.enabled() && row.enabled()) && !eligible(row))
                throw HomeBannerRequest.invalid("#/items/" + index + "/target", "INVALID_REFERENCE");
            if (row.coverAttachmentId() != null
                    && (previous == null || !Objects.equals(previous.coverAttachmentId(), row.coverAttachmentId())))
                newCovers.putIfAbsent(row.coverAttachmentId(), index);
        }

        newCovers.forEach((id, index) -> {
            if (!images.lockReusable(id, actor))
                throw HomeBannerRequest.invalid("#/items/" + index + "/coverAttachmentId", "INVALID_REFERENCE");
        });

        if (current.equals(requested)) return versioned(current, revision);
        banners.clear();
        for (int index = 0; index < requested.size(); index++) banners.insert(index, requested.get(index));
        if (banners.advanceRevision() != 1) throw AdminQuery.unavailable();
        return versioned(requested, revision + 1);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 5)
    public OptionPage options(long actor, Type type, String query, String exactId, int page, int size) {
        authorize(actor);
        if (query != null && exactId != null) throw AdminQuery.invalid("q");
        String q = query == null ? null : query.strip();
        if (q != null && (q.isEmpty() || query.codePointCount(0, query.length()) > 120))
            throw AdminQuery.invalid("q");
        if (page < 1 || size < 1 || size > 100) throw AdminQuery.invalid("page/pageSize");
        if (exactId != null && (exactId.isEmpty() || exactId.codePointCount(0, exactId.length()) > 128))
            throw AdminQuery.invalid("targetId");
        Long id = exactId == null ? null : AdminQuery.storedId(exactId);
        if (exactId != null && id == null) return new OptionPage(List.of(), page, size, 0);
        long offset = (page - 1L) * size;
        return switch (type) {
            case ARTICLE -> {
                var result = articles.publicOptions(id, q, offset, size);
                yield new OptionPage(result.items().stream().map(row -> option(type, row.id(), row.title())).toList(),
                        page, size, result.totalItems());
            }
            case ACTIVITY -> {
                var result = activities.publicOptions(id, q, offset, size);
                yield new OptionPage(result.items().stream().map(row -> option(type, row.id(), row.title())).toList(),
                        page, size, result.totalItems());
            }
        };
    }

    private void authorize(long actor) {
        authorization.requirePublishedPlatformDuty(actor, "HOME_BANNER_MANAGE", "HOME", "home-banner:manage");
    }

    private boolean eligible(Row row) {
        return switch (row.targetType()) {
            case ARTICLE -> articles.publicCard(row.targetId(), true) != null;
            case ACTIVITY -> activities.publicCard(row.targetId(), false, true) != null;
        };
    }

    private static long requireRevision(Long revision) {
        if (revision == null) throw AdminQuery.unavailable();
        return revision;
    }

    private static Versioned versioned(List<Row> rows, long revision) {
        var settings = rows.stream().map(row -> new BannerSetting(target(row.targetType(), row.targetId()), row.enabled(), row.titleOverride(),
                row.coverAttachmentId() == null ? null : row.coverAttachmentId().toString())).toList();
        return new Versioned(new Configuration(settings), StrongEtag.of(RESOURCE, 1, revision));
    }

    static Target target(Type type, long id) {
        return new Target(type, Long.toString(id));
    }

    private static Option option(Type type, long id, String title) {
        return new Option(new Target(type, Long.toString(id)), title);
    }
}
