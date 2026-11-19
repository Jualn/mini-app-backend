package cn.jualn.miniapp.module.home.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigInteger;
import java.time.OffsetDateTime;
import java.util.List;

public final class HomeContent {
    private HomeContent() {}

    public enum Type { ARTICLE, ACTIVITY }

    public record Target(Type targetType, String targetId) {}
    public record Configuration(List<BannerSetting> items) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BannerSetting(Target target, boolean enabled, String titleOverride, String coverAttachmentId) {}
    public record Option(Target target, String title) {}
    public record OptionPage(List<Option> items, int page, int pageSize, BigInteger totalItems) {
        public OptionPage(List<Option> items, int page, int pageSize, long totalItems) {
            this(items, page, pageSize, BigInteger.valueOf(totalItems));
        }
    }

    public record Cover(String url) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Banner(Target target, String title, Cover cover) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Article(String id, String title, String summary, OffsetDateTime publishedAt, Cover cover) {}
    public record Stage(String state, OffsetDateTime evaluatedAt) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Schedule(String kind, OffsetDateTime time, OffsetDateTime startTime, OffsetDateTime endTime) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Activity(String activityId, String title, Stage stage, Schedule schedule, Cover cover) {}

    public enum Status { READY, EMPTY, UNAVAILABLE }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Module<T>(Status status, List<T> items) {
        public static <T> Module<T> of(List<T> items) {
            return new Module<>(items.isEmpty() ? Status.EMPTY : Status.READY, items);
        }

        public static <T> Module<T> unavailable() {
            return new Module<>(Status.UNAVAILABLE, null);
        }
    }

    public record Summary(OffsetDateTime evaluatedAt, Module<Banner> banners,
                          Module<Article> latestArticles, Module<Activity> upcomingActivities) {}
}
