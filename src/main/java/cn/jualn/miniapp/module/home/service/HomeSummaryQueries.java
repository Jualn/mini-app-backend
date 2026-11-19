package cn.jualn.miniapp.module.home.service;

import cn.jualn.miniapp.common.web.AdminQuery;
import cn.jualn.miniapp.module.activity.service.ActivityQueries;
import cn.jualn.miniapp.module.article.service.ArticleService;
import cn.jualn.miniapp.module.home.mapper.HomeBannerMapper;
import cn.jualn.miniapp.module.home.mapper.HomeBannerMapper.Row;
import cn.jualn.miniapp.module.home.model.HomeContent.*;
import cn.jualn.miniapp.module.media.service.PublicImageReuse;
import cn.jualn.miniapp.module.timeline.bo.TimelineFactBO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 2)
public class HomeSummaryQueries {
    private final HomeBannerMapper banners;
    private final ArticleService articles;
    private final ActivityQueries activities;
    private final PublicImageReuse images;

    public List<Banner> banners() {
        if (banners.revision() == null) throw AdminQuery.unavailable();
        List<Banner> result = new ArrayList<>();
        for (var row : banners.items()) {
            if (!row.enabled()) continue;
            Banner banner = switch (row.targetType()) {
                case ARTICLE -> {
                    var article = articles.publicCard(row.targetId(), false);
                    yield article == null ? null : banner(row, article.title(), article.coverAttachmentId());
                }
                case ACTIVITY -> {
                    var activity = activities.publicCard(row.targetId(), true, false);
                    yield activity == null ? null : banner(row, activity.title(), activity.coverAttachmentId());
                }
            };
            if (banner != null) result.add(banner);
        }
        return List.copyOf(result);
    }

    public List<Article> latestArticles(OffsetDateTime at) {
        return articles.recentPublicCards("PARTY_NEWS", 5).stream()
                .map(card -> new Article(Long.toString(card.id()), card.title(), card.summary(),
                        card.publishedAt().atOffset(at.getOffset()), cover(images.publicUrl(card.coverAttachmentId()))))
                .toList();
    }

    public List<Activity> upcomingActivities(OffsetDateTime at) {
        return activities.upcomingPublicCards(at.toLocalDateTime(), 2).stream()
                .map(row -> new Activity(Long.toString(row.card().id()), row.card().title(), new Stage(row.state(), at),
                        schedule(row.main(), at), cover(images.publicUrl(row.card().coverAttachmentId()))))
                .toList();
    }

    private Banner banner(Row row, String title, Long targetCover) {
        String configured = row.coverAttachmentId() == null ? null : images.reusableUrl(row.coverAttachmentId());
        return new Banner(HomeBannerService.target(row.targetType(), row.targetId()), row.titleOverride() == null ? title : row.titleOverride(),
                cover(configured == null ? images.publicUrl(targetCover) : configured));
    }

    private static Schedule schedule(TimelineFactBO node, OffsetDateTime evaluatedAt) {
        var start = node.getStartTime().atOffset(evaluatedAt.getOffset());
        return node.getEndTime() == null ? new Schedule("EXACT_POINT", start, null, null)
                : new Schedule("EXACT_RANGE", null, start, node.getEndTime().atOffset(evaluatedAt.getOffset()));
    }

    private static Cover cover(String url) {
        return url == null ? null : new Cover(url);
    }
}
